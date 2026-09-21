# Reservation Cancellation Requirements

Status: Requirements resolved; design and task decomposition documented.

## Context

Reservation clients need to cancel one existing reservation without waiting for Inventory to
release the associated item. The public operation is
`POST /api/v1/reservations/{id}/cancel`. It has no request body, requires no
`Idempotency-Key`, and returns `204 No Content` after Reservation commits its local work. A
successful response reports only the Reservation-side outcome: Inventory can continue to report
the item as `RESERVED` until it consumes the cancellation event.

A reservation in `HELD` or `CONFIRMED` transitions to `CANCELLED`. The transition and one durable
outbox record commit in the same Reservation-owned PostgreSQL transaction. An already `CANCELLED`
reservation is a successful idempotent no-op that creates no further event. Concurrent calls to
cancel the same reservation must converge on one state transition and one logical event.

After commit, an outbox relay publishes the logical event to
`rentflow.reservation.cancelled.v1`. Delivery is reliable and at least once: ambiguous broker
acknowledgements may cause the same event to be published more than once, but every attempt for an
outbox record preserves its event identifier, JSON value, and Kafka record key. Inventory owns
consumer idempotency and uses the stable event identifier to prevent duplicate lifecycle effects.

The version-1 record key is the reservation's exact case-sensitive Inventory serial number. Its
UTF-8 JSON value contains only `eventId`, `eventType`, `eventVersion`, `occurredAt`, and
`serialNumber`. It deliberately carries no reservation, customer, order, actor, or cancellation
reason. This contract matches Inventory's implemented consumer in
`rent-flow-inventory/.specs/async-reservation-cancellation`.

The existing full-replacement and hard-delete operations remain unchanged temporarily. They can
therefore still set `CANCELLED` or remove a reservation without producing a cancellation event.
Closing those bypasses is planned separately; the guarantees in this feature apply only to the new
cancel operation.

### Integration boundary

Reservation owns the cancellation endpoint, event contract, source topic semantics, outbox, and
publisher. Inventory owns its consumer group, inbox, lifecycle reaction, retries, and dead-letter
handling. Each service accesses only its own database schema. The common repository owns the local
Kafka broker topology and Compose wiring, but not feature topic names or event payloads.

## User stories with acceptance criteria

### US1 — Cancel one reservation idempotently

As a reservation client, I want to cancel one reservation by identifier so that its local state is
terminal without making my request wait for Inventory.

- **AC1.1 (Event-driven):** When `POST /api/v1/reservations/{id}/cancel` identifies a `HELD` or
  `CONFIRMED` reservation and the local transaction commits, the reservation service shall change
  that reservation's status to `CANCELLED` and return `204 No Content` with an empty body.
- **AC1.2 (Event-driven):** When the cancel operation identifies an already `CANCELLED`
  reservation, the reservation service shall leave it unchanged, create no outbox record, and
  return `204 No Content` with an empty body.
- **AC1.3 (Unwanted):** If the reservation identifier does not exist, then the reservation service
  shall return `404 RESERVATION_NOT_FOUND` and shall change neither reservations nor outbox
  records.
- **AC1.4 (Unwanted):** If the path identifier is not a valid UUID, then the reservation service
  shall return `400 VALIDATION_FAILED` and shall change neither reservations nor outbox records.
- **AC1.5 (Ubiquitous):** The reservation service shall accept the cancel operation without an
  `Idempotency-Key` or a request body.
- **AC1.6 (Event-driven):** When two or more requests concurrently cancel the same eligible
  reservation, the reservation service shall commit exactly one transition to `CANCELLED`, create
  exactly one logical cancellation event, and return `204 No Content` to every request that
  completes after observing either the transition or its committed result.
- **AC1.7 (Unwanted):** If the local cancellation transaction rolls back or cannot commit, then the
  reservation service shall not report a successful cancellation and shall persist neither a
  partial status transition nor its outbox record.

### US2 — Record the cancellation event atomically

As a service operator, I want every cancellation transition paired with a durable event so that a
committed cancellation cannot be lost between PostgreSQL and Kafka.

- **AC2.1 (Event-driven):** When a reservation transitions from `HELD` or `CONFIRMED` to
  `CANCELLED`, the reservation service shall persist exactly one outbox record in the same local
  database transaction as that transition.
- **AC2.2 (Ubiquitous):** The reservation service shall confine the outbox table, constraints,
  indexes, and Flyway migrations to the Reservation-owned `reservation` schema.
- **AC2.3 (Ubiquitous):** The reservation service shall assign each logical cancellation event one
  canonical lowercase UUID v4 `eventId` that remains unchanged for the lifetime of its outbox
  record.
- **AC2.4 (Ubiquitous):** The reservation service shall serialize a version-1 cancellation event as
  a UTF-8 JSON object containing exactly `eventId`, `eventType`, `eventVersion`, `occurredAt`, and
  `serialNumber`, with no null fields and no additional fields.
- **AC2.5 (Ubiquitous):** The reservation service shall emit `eventType` as the case-sensitive
  value `ReservationCancelled`, `eventVersion` as the integer `1`, `occurredAt` as an RFC 3339 UTC
  instant with a trailing `Z`, and `serialNumber` as the reservation's exact case-sensitive serial
  number at the committed transition.
- **AC2.6 (Ubiquitous):** The reservation service shall use the UTF-8 encoding of the event's exact
  `serialNumber` as its Kafka record key and shall not include a `reservationId` in the event.
- **AC2.7 (Unwanted):** If an operation does not commit a `HELD`-to-`CANCELLED` or
  `CONFIRMED`-to-`CANCELLED` transition through the cancel endpoint, then the reservation service
  shall create no cancellation outbox record for that operation.

### US3 — Publish committed cancellation events reliably

As a service operator, I want committed outbox events relayed with stable identities so that
Inventory eventually receives them and can safely tolerate duplicate delivery.

- **AC3.1 (Event-driven):** When a cancellation outbox record is eligible for publication, the
  reservation service shall publish its event to `rentflow.reservation.cancelled.v1` using its
  persisted serial-number key and JSON value.
- **AC3.2 (Event-driven):** When publication of an outbox record is attempted more than once, the
  reservation service shall reuse the identical `eventId`, Kafka record key, and JSON value on
  every attempt.
- **AC3.3 (Ubiquitous):** The reservation service shall publish cancellation events with Kafka
  `acks=all` and producer idempotence enabled.
- **AC3.4 (Event-driven):** When Kafka confirms publication, the reservation service shall record
  the outbox event as published only after receiving that broker acknowledgement.
- **AC3.5 (Unwanted):** If Kafka does not confirm publication, then the reservation service shall
  retain the outbox event as unpublished and make it eligible for another publication attempt.
- **AC3.6 (State-driven):** While Kafka is unavailable or an event remains unpublished, the
  reservation service shall continue serving cancellation requests whose local database
  transactions can commit and shall not wait for Inventory consumption.
- **AC3.7 (Ubiquitous):** The reservation service shall treat delivery as at least once and shall
  not claim exactly-once Kafka publication or use a distributed transaction spanning PostgreSQL
  and Kafka.

### US4 — Honor the Inventory contract and service boundaries

As an integration operator, I want Reservation's producer behavior to match Inventory's deployed
consumer contract so that cancellation events are processable without cross-service database
access.

- **AC4.1 (Ubiquitous):** The reservation service shall define the source topic with three
  partitions, `delete` cleanup, and seven-day retention, using replication factor one in local and
  test environments and targeting replication factor three with minimum in-sync replicas two in
  production.
- **AC4.2 (Ubiquitous):** The reservation service shall preserve Kafka ordering for one Inventory
  item by publishing every cancellation event for the same case-sensitive serial-number key.
- **AC4.3 (Ubiquitous):** The reservation service shall not call Inventory synchronously during
  cancellation and shall not wait for an Inventory acknowledgement or result event before
  returning the HTTP response.
- **AC4.4 (Ubiquitous):** The reservation service shall access only the `reservation` database
  schema and shall communicate cancellation facts to Inventory only through the public Kafka
  contract.
- **AC4.5 (Ubiquitous):** The reservation service shall leave the behavior of the existing
  `PUT /api/v1/reservations/{id}` and `DELETE /api/v1/reservations/{id}` operations unchanged in
  this increment.
- **AC4.6 (Ubiquitous):** The reservation service shall document the HTTP cancellation contract,
  eventual-consistency boundary, at-least-once delivery, version-1 event contract, topic policy,
  and operational recovery assumptions.

## Out of scope

- Cancelling multiple reservations or every reservation belonging to an order in one request.
- Requiring an idempotency key or maintaining a cancellation response ledger.
- Accepting or persisting a cancellation reason, actor, comment, or other cancellation metadata.
- Adding a `cancelledAt` field to the public reservation representation.
- Removing or changing the existing hard-delete operation.
- Preventing the existing full-replacement operation from setting status to `CANCELLED`.
- Coordinating the cancel operation with concurrent legacy replacement or hard-delete requests.
- Authentication, authorization, or audit-principal integration.
- Changing Inventory's implemented consumer, inbox, retry, dead-letter, history, or lifecycle
  behavior.
- Publishing an Inventory acknowledgement, cancellation result, or compensation event.
- Carrying a reservation ownership token in version 1 or preventing the first delivery of a stale
  event from releasing an item reassigned outside the event flow.
- Guaranteeing exactly-once Kafka delivery or coordinating PostgreSQL and Kafka in one distributed
  transaction.
- Providing unlimited recovery after both the seven-day source-topic record and the corresponding
  recoverable outbox state are unavailable.
- Defining production Kafka security, credentials, ACL provisioning, broker topology, backup, or
  disaster recovery.

## Resolved questions

1. **Outbox persistence.** Store the immutable event ID, exact record key, exact serialized JSON,
   and PostgreSQL occurrence time beside mutable publication timestamps, attempt count, retry time,
   and a bounded failure code. Use partial FIFO and cleanup indexes. See `design.md` §4.
2. **Relay mechanism.** Poll PostgreSQL on a one-second fixed delay and drain within explicit event
   and runtime bounds; do not introduce CDC infrastructure. See `design.md` §5.1.
3. **Multi-instance coordination.** Use one transaction-scoped PostgreSQL advisory lock and lock the
   globally oldest unpublished row through its broker acknowledgement. This requires no lease or
   abandoned-claim recovery and deliberately preserves FIFO order. See `design.md` §5.2–§5.3.
4. **Timeout and retry.** Bound one Kafka attempt with explicit producer/send timeouts, then retry
   indefinitely from persisted state with one-second exponential backoff, a five-minute nominal
   cap, and 20-percent jitter. See `design.md` §5.3–§5.5.
5. **Retention and cleanup.** Retain unpublished rows indefinitely and delete broker-acknowledged
   rows after 30 days in bounded daily chunks, preserving a manual-recovery window beyond the
   seven-day source retention. See `design.md` §6.
6. **Topic provisioning.** Create the source topic explicitly under the local profile and in
   isolated Kafka integration tests, use external production provisioning, and add Kafka wiring to
   common Compose. See `design.md` §7.
7. **Operations and health.** Keep Kafka outside readiness so the outbox can buffer outages; expose
   bounded publication, retry, pending-age, and cleanup telemetry without payloads or
   high-cardinality metric labels. See `design.md` §8.
