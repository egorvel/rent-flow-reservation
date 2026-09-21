# Reservation Cancellation Design

Status: Design resolved; implementation tasks documented.

## 1. Scope and architecture

### 1.1 Design boundary

This feature adds `POST /api/v1/reservations/{id}/cancel`, an atomic local cancellation and a
transactional-outbox producer for Inventory's implemented `ReservationCancelled` Kafka contract.
The request commits a Reservation status change and an outbox event together, then returns without
calling Inventory or waiting for Kafka. A scheduled relay later publishes the persisted event.

The design uses a PostgreSQL-polled outbox rather than CDC. It does not add Debezium, a schema
registry, a Kafka transaction, an Inventory HTTP call, an Inventory result event, or a distributed
transaction. Kafka publication is at least once; Inventory's durable inbox supplies the
effectively-once lifecycle effect for a stable event identifier.

The guarantees apply only to the new cancel operation. Existing replacement and hard deletion stay
unchanged, including their ability to bypass the event path. Coordination between cancellation and
those legacy mutations is explicitly outside this increment.

### 1.2 Resolved design decisions

| Concern | Decision |
| --- | --- |
| HTTP operation | `POST /api/v1/reservations/{id}/cancel`, empty request and `204` response |
| Command idempotency | Current `CANCELLED` state is the idempotency record; no key or response ledger |
| Cancellation concurrency | Pessimistic lock on the reservation row |
| Atomicity | Reservation transition and immutable outbox row in one PostgreSQL transaction |
| Event storage | Exact compact JSON text and exact record key are persisted once and never regenerated |
| Event time | One PostgreSQL `clock_timestamp()` sampled after the reservation lock is acquired |
| Relay | Scheduled polling with one active cluster-wide FIFO publisher |
| Relay coordination | Transaction-scoped PostgreSQL advisory lock; no claim state or leases |
| Delivery | Synchronous broker acknowledgement inside a bounded database transaction |
| Retry | Indefinite persisted exponential backoff, nominally 1 second to 5 minutes with 20% jitter |
| Cleanup | Unpublished rows indefinite; acknowledged rows retained for 30 days |
| Topic provisioning | Reservation creates the source topic only for local/test use; production is external |
| Health | Kafka is excluded from readiness; backlog and failures are operational signals |

### 1.3 Runtime components

```text
POST /api/v1/reservations/{id}/cancel
  -> ReservationController
       -> ReservationCancellationService [one local transaction]
            -> ReservationRepository [reservation row lock]
            -> ReservationCancellationOutboxRepository [PostgreSQL time + insert]

ReservationCancellationOutboxRelayScheduledService [fixed-delay polling]
  -> ReservationCancellationOutboxRelayService [one REQUIRES_NEW transaction per event]
       -> ReservationCancellationOutboxRepository [cluster lock + oldest row lock]
       -> KafkaTemplate<byte[], byte[]> [wait for broker acknowledgement]

ReservationCancellationOutboxCleanupScheduledService [daily]
  -> ReservationCancellationOutboxCleanupService [bounded chunks]
       -> ReservationCancellationOutboxRepository
```

- `ReservationController` owns HTTP/OpenAPI mapping and returns an empty `204` response.
- `ReservationCancellationService` owns row locking, state transition, event construction,
  one-time serialization, and atomic outbox persistence.
- `ReservationCancellationOutbox` is the JPA entity for immutable event data plus mutable delivery
  metadata.
- `ReservationCancellationOutboxRepository` owns PostgreSQL time, advisory locking, FIFO selection,
  relay statistics, and cleanup SQL.
- `ReservationCancellationOutboxRelayService` performs one publish attempt transaction.
- `ReservationCancellationOutboxRelayScheduledService` bounds draining and records relay metrics.
- `ReservationCancellationOutboxCleanupService` and its scheduler follow the existing bounded
  cleanup pattern for acknowledged events.
- `ReservationKafkaConfig` and validated cancellation properties own the producer and local topic
  configuration.

All types stay in the existing `controller`, `service`, `repository`, `model`, and `config`
packages. No new architectural layer or shared event library is introduced.

## 2. HTTP contract and cancellation transaction

### 2.1 HTTP contract

The controller adds:

```http
POST /api/v1/reservations/{id}/cancel
```

The operation has no request representation and declares no `consumes` media type. The handler has
only a UUID path parameter, does not inspect `Idempotency-Key`, and adds no replay or expiry headers.
An empty request succeeds without `Content-Type`. An unsolicited body or header has no defined
business meaning and is not incorporated into the command.

Responses are:

| Condition | Status | Body |
| --- | --- | --- |
| `HELD` or `CONFIRMED` cancellation commits | `204 No Content` | Empty |
| Reservation was already `CANCELLED` | `204 No Content` | Empty |
| UUID does not identify a reservation | `404 Not Found`, `RESERVATION_NOT_FOUND` | Existing Problem Details |
| Path value is not a UUID | `400 Bad Request`, `VALIDATION_FAILED` | Existing Problem Details |
| Serialization, database, or commit failure | `500 Internal Server Error`, `INTERNAL_ERROR` | Existing safe Problem Details |

The OpenAPI operation ID is `cancelReservation`. Its description states that `204` means only the
Reservation transaction committed or the reservation was already cancelled; it does not mean
Kafka publication or Inventory release has completed.

### 2.2 Transaction algorithm

`ReservationCancellationService.cancel(UUID)` is one ordinary Spring transaction at the existing
`READ COMMITTED` isolation level:

1. Load the reservation through `ReservationRepository.findForUpdateById` with
   `PESSIMISTIC_WRITE`. If it does not exist, throw the existing `ReservationNotFoundException`.
2. If its status is already `CANCELLED`, return without sampling time, mutating the row, or creating
   an outbox entity.
3. The only remaining model states are `HELD` and `CONFIRMED`. Read one PostgreSQL
   `clock_timestamp()` after acquiring the row lock; this is `occurredAt` and the initial
   `nextAttemptAt`.
4. Generate one UUID v4 with `UUID.randomUUID()`. Its canonical lowercase `toString()` is the
   event's `eventId`.
5. Construct the version-1 event in the field order specified by §3.1. Use the configured Jackson
   mapper once to create compact JSON text; validate its UTF-8 size is at most 4096 bytes.
6. Change the reservation status to `CANCELLED` and persist one
   `ReservationCancellationOutbox` whose primary key is the event ID, record key is the exact
   serial number, payload is the serialized text, and occurrence/initial-attempt time is the
   sampled database time.
7. Return from the service. Spring commits both managed entities before the controller sends
   `204`.

An event-serialization or persistence exception escapes the service transaction. The existing
safe `500` mapping applies and PostgreSQL commits neither change. The request path creates no
Kafka producer record and remains available during a Kafka outage.

### 2.3 Natural and concurrent idempotency

There is no cancellation idempotency-key table. The reservation row itself is the durable command
state. A replay after a committed cancellation sees `CANCELLED`, returns `204`, and creates no
event.

Concurrent cancel requests for the same UUID serialize on the pessimistic row lock. The winner
observes `HELD` or `CONFIRMED`, commits the transition and one event, and releases the lock. Each
follower then observes `CANCELLED` and returns the no-op result. A winner rollback releases the lock
without either write, allowing the next waiter to perform the transition. This provides one
logical event without an advisory command lock or client key.

The design does not add optimistic versioning to `Reservation`. It also does not make the existing
replacement or delete paths acquire the cancellation row lock. Their concurrent interaction with
cancellation remains outside scope as recorded in §2.4.

### 2.4 Legacy mutation paths

`PUT /api/v1/reservations/{id}` continues to replace all mutable fields and can set status to
`CANCELLED` without an outbox event. `DELETE /api/v1/reservations/{id}` continues to hard-delete a
reservation without an event. Their request, response, validation, and persistence behavior do not
change.

The outbox has no foreign key to `reservations`. Consequently, an event committed by the new cancel
operation survives a later hard deletion and remains publishable. A legacy delete or replacement
that bypasses cancellation creates no event, which is an accepted temporary limitation rather than
an outbox repair case.

## 3. Kafka event contract

### 3.1 Version-1 source record

The destination is `rentflow.reservation.cancelled.v1`. The Kafka key is the UTF-8 encoding of the
exact case-sensitive serial number. The Kafka value is a compact UTF-8 JSON object no larger than
4096 bytes and contains these fields in this order:

```json
{"eventId":"d880f919-2b5c-4f7e-a56d-e047e7d932a6","eventType":"ReservationCancelled","eventVersion":1,"occurredAt":"2026-09-15T15:30:00Z","serialNumber":"DRILL-001"}
```

| Field | JSON type | Producer rule |
| --- | --- | --- |
| `eventId` | string | Canonical lowercase 36-character UUID v4 generated once |
| `eventType` | string | Exactly `ReservationCancelled`, case-sensitive |
| `eventVersion` | integer | Exactly `1` |
| `occurredAt` | string | PostgreSQL occurrence `Instant.toString()`, RFC 3339 UTC with trailing `Z` |
| `serialNumber` | string | Exact reservation value matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |

There are no other members and no nulls. In particular, the payload and headers carry no
`reservationId`, customer, order, actor, reason, correlation ID, or Java type metadata. The
producer uses `KafkaTemplate<byte[], byte[]>` with byte-array serializers rather than a JSON Kafka
serializer, preventing type headers and reserialization.

### 3.2 Stable bytes, key, and partition ordering

The cancellation transaction serializes the payload exactly once. PostgreSQL `text` preserves that
JSON text, and every relay attempt obtains key and value bytes with UTF-8 from the persisted
`recordKey` and `payload`. The relay never rebuilds the event from a current reservation or from
individual outbox fields. JSON formatting, field order, event ID, occurrence time, serial case,
record key, and value bytes therefore remain stable across retries and process restarts.

Kafka's default key partitioning sends identical serial keys to the same partition. Producer
idempotence preserves order for retries within a producer session. The relay's global FIFO rule in
§5.2 prevents different Reservation instances from overtaking an older committed outbox event,
including events for the same serial across separate reservation lifecycles.

### 3.3 Compatibility

The topic suffix is the wire-contract major version. Optional additive fields are compatible with
Inventory's consumer, but this producer emits exactly the five version-1 fields for this increment.
Removing a field, changing a JSON type or meaning, changing key semantics, or adding required data
is breaking and requires a new topic/version with a coordinated consumer migration.

Version 1 carries no reservation ownership token. Inventory can suppress a redelivery whose event
ID is already in its inbox, but it cannot recognize the first delivery of an old event after an
out-of-band release and reassignment. That consumer limitation is accepted for this increment;
operators must account for it before manually replaying an old event.

## 4. Outbox persistence

### 4.1 Append-only V3 migration and ownership

Add `V3__reservation_cancellation_outbox.sql`. V1 and V2 remain unchanged. The migration creates
only objects in the Reservation-owned `reservation` schema:

- `reservation.reservation_cancellation_outbox`;
- a partial FIFO index for unpublished rows; and
- a partial cleanup index for published rows.

No Reservation migration creates or alters a topic, consumer object, Inventory table, or another
service's Flyway history. Hibernate remains `ddl-auto: validate`.

### 4.2 Table shape and invariants

`reservation_cancellation_outbox` contains:

| Column | PostgreSQL type | Rule |
| --- | --- | --- |
| `event_id` | `uuid` | Primary key and stable logical event ID |
| `record_key` | `varchar(64)` | Exact serial; existing serial-number regex |
| `payload` | `text` | Valid JSON object with unique keys; UTF-8 size at most 4096 bytes |
| `occurred_at` | `timestamptz(6)` | PostgreSQL occurrence time; immutable |
| `published_at` | `timestamptz(6)` nullable | Broker-confirmed publication time |
| `attempt_count` | `integer` | Non-negative; total relay attempts whose metadata transaction committed |
| `next_attempt_at` | `timestamptz(6)` nullable | Required while unpublished; null after success |
| `last_failure_at` | `timestamptz(6)` nullable | Time of the latest committed failed attempt |
| `last_failure_code` | `varchar(32)` nullable | `KAFKA_SEND_TIMEOUT`, `KAFKA_SEND_FAILED`, or `RELAY_INTERRUPTED` |

Database checks enforce:

- `record_key` syntax;
- `payload IS JSON OBJECT WITH UNIQUE KEYS` and `octet_length(payload) <= 4096`;
- `attempt_count >= 0`;
- `published_at IS NULL` exactly when `next_attempt_at IS NOT NULL`;
- the two last-failure columns are both null or both non-null; and
- delivery timestamps do not precede `occurred_at`.

The FIFO index is `(occurred_at, event_id) WHERE published_at IS NULL`. The cleanup index is
`(published_at, event_id) WHERE published_at IS NOT NULL`. There is deliberately no status enum,
claim owner, lease, reservation foreign key, topic column, exception message, or stack trace.
`published_at` distinguishes pending from acknowledged; the topic is fixed by versioned
configuration.

### 4.3 Entity and repository

`ReservationCancellationOutbox` maps the table directly. Event ID, key, payload, and occurrence
time are non-updatable after construction. Its behavior methods only:

- increment `attemptCount` and record a bounded failure plus the next attempt time; or
- increment `attemptCount`, set `publishedAt`, and clear `nextAttemptAt` after acknowledgement.

The entity retains the last failure metadata after a later success for operational diagnosis.

`ReservationCancellationOutboxRepository` extends `JpaRepository` and adds narrowly scoped native
queries for:

- `clock_timestamp()`;
- the fixed `pg_try_advisory_xact_lock` relay lock;
- the oldest unpublished row under `FOR UPDATE`;
- pending count and oldest pending age using PostgreSQL time; and
- chunked acknowledged-row cleanup using `FOR UPDATE SKIP LOCKED`.

Spring Data locking supplies `findForUpdateById` on `ReservationRepository`. Java local variables
remain explicitly typed, and repository/entity code follows the existing persistence patterns.

## 5. Outbox relay

### 5.1 Polling schedule and drain bounds

`ReservationCancellationOutboxRelayScheduledService` uses a configurable fixed delay of one
second. On each invocation it calls `ReservationCancellationOutboxRelayService.publishOldest()`
repeatedly until one of these occurs:

- no unpublished row exists;
- the oldest row's backoff has not elapsed;
- another service instance owns the relay lock;
- one publication fails;
- 100 events have been published; or
- the invocation reaches its five-second runtime budget.

The per-invocation event limit and runtime budget bound scheduler and database use while allowing a
backlog to drain faster than one event per scheduler tick. The scheduler uses `System.nanoTime()`
only for its runtime budget; all persisted and backlog timestamps come from PostgreSQL.

### 5.2 Cluster coordination and FIFO ordering

Each `publishOldest()` call is a `REQUIRES_NEW` transaction in a separate service bean. It first
attempts one fixed transaction-scoped advisory lock dedicated to this outbox. Failure to acquire it
returns immediately. PostgreSQL releases the lock on commit, rollback, connection loss, or process
death, so no claim row, lease expiry, or abandoned-claim recovery exists.

After acquiring the advisory lock, the transaction loads the oldest committed unpublished row
visible at selection, ordered by `occurred_at, event_id`, with a pessimistic row lock. It does not
filter by `next_attempt_at` in SQL. If that oldest row is not eligible yet, the relay returns
without examining later rows. A failed event therefore blocks all later committed events until its
backoff expires and it succeeds. This deliberate FIFO policy is stricter than the required
per-serial ordering and trades throughput for a simple, auditable order guarantee. Transactions
that have not committed are not visible to the relay; normal same-serial lifecycles still commit
the earlier cancellation before another reservation for that serial can be created and cancelled.

Only one event is sent per database transaction. The scheduler can immediately start another
transaction after success. Multiple Reservation instances can alternate transactions, but the
advisory lock and oldest-row selection prevent concurrent publication or overtaking.

### 5.3 One publication attempt

For an eligible row, the relay:

1. Converts the persisted `recordKey` and `payload` to UTF-8 bytes.
2. Calls `KafkaTemplate<byte[], byte[]>.send(topic, key, value)` and waits at most 50 seconds for
   its result. `send()` itself can block for producer metadata/buffer allocation for at most the
   configured five-second `max.block.ms`.
3. On a successful broker result, samples PostgreSQL `clock_timestamp()`, records a successful
   attempt, sets `publishedAt`, clears `nextAttemptAt`, and commits.
4. On timeout, interruption, or another send failure, samples PostgreSQL time, records the bounded
   failure category, calculates §5.4's next attempt, commits the failed-attempt metadata, and stops
   the current drain run. Interrupted status is restored after the transactional result is safely
   recorded.

The database transaction, advisory lock, and outbox row lock remain open while waiting for Kafka.
The maximum wait is intentionally bounded. Cancellation HTTP transactions never wait on this lock
or network call because they only insert new rows.

Producer defaults are explicit:

| Setting | Default |
| --- | --- |
| Bootstrap servers | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `acks` | `all` |
| `enable.idempotence` | `true` |
| `max.in.flight.requests.per.connection` | `5` |
| `request.timeout.ms` | `30000` |
| `delivery.timeout.ms` | `45000` |
| `max.block.ms` | `5000` |
| Key/value serializer | `ByteArraySerializer` |

Kafka client retries remain governed by `delivery.timeout.ms`; the design does not set a smaller
`retries` count. `delivery.timeout.ms` exceeds `request.timeout.ms + linger.ms`. No
`transactional.id` is configured because a Kafka transaction cannot make the PostgreSQL commit
atomic.

### 5.4 Persisted retry and backoff

Outbox retries never exhaust and there is no producer dead-letter topic. For failed attempt number
`N` (starting at one), the nominal delay is:

```text
min(1 second * 2^(N - 1), 5 minutes)
```

The relay applies a random factor from 0.8 through 1.2. Five minutes is the nominal cap, so the
actual jittered delay at the cap is between four and six minutes. Exponent calculation saturates
before numeric overflow. `nextAttemptAt` is the PostgreSQL failure time plus that delay.

Backoff settings are validated configuration properties: positive initial delay, multiplier at
least one, maximum not below initial, and jitter in `[0, 1)`. Defaults are one second, multiplier
two, five-minute nominal maximum, and 0.2 jitter. Test profiles use millisecond values without
changing production semantics.

Because payloads are locally constructed, schema-checked, and at most 4096 bytes, a permanently
bad individual event is not an expected business case. Nevertheless, strict FIFO means any
persistent failure blocks the relay and must trigger operator investigation rather than being
silently skipped.

### 5.5 At-least-once failure windows

The relay cannot atomically commit Kafka and PostgreSQL. Two ambiguity windows intentionally lead
to safe duplicate publication:

- Kafka accepts a record but the acknowledgement is lost or arrives after the relay timeout. The
  row remains unpublished and is sent again.
- Kafka acknowledges, but the database update or commit fails. The row remains unpublished and is
  sent again.

Every duplicate uses identical persisted bytes and event ID. Kafka producer idempotence suppresses
eligible retries within one producer session; it does not cover restarts or the database/Kafka
ambiguity. Inventory's inbox owns that cross-session duplicate suppression. The design never
claims exactly-once Kafka delivery.

## 6. Retention and cleanup

Unpublished rows are retained indefinitely, including during extended Kafka outages. No cleanup
query can select them.

Published rows are retained until `published_at` is at least 30 days old. At 03:30 UTC by default,
`ReservationCancellationOutboxCleanupScheduledService` deletes eligible rows in chunks of 1000,
ordered by `published_at, event_id`, and stops after a configurable 60-second runtime budget. Each
chunk runs in `REQUIRES_NEW`; `FOR UPDATE SKIP LOCKED` makes concurrent service instances safe.
The schedule is offset from the existing 03:00 reservation-creation cleanup.

Thirty days exceeds the source topic's seven-day retention and preserves the exact key/value for a
bounded manual-recovery window. Published rows are not republished automatically merely because
Inventory lagged beyond topic retention. During an incident, operators verify Inventory state and
inbox history, then deliberately republish the retained key and payload under the same event ID if
safe. No administrative HTTP endpoint or automatic reconciliation is added.

## 7. Topic and environment configuration

### 7.1 Topic policy and ownership

The Reservation-owned source topic is configured as follows:

| Setting | Local/test | Production target |
| --- | --- | --- |
| Name | `rentflow.reservation.cancelled.v1` | Same |
| Partitions | `3` | `3` |
| Cleanup policy | `delete` | `delete` |
| Retention | `604800000` ms (7 days) | Same |
| Replication factor | `1` | `3` |
| Minimum in-sync replicas | Broker/topic local default | `2` |

Automatic topic creation remains disabled. The partition count is fixed for version 1 because
increasing it changes serial-key partition mapping and can reorder records already in the topic.

Reservation needs write/describe access to the source topic in production. Inventory needs
read/describe access and owns its consumer group and DLT permissions. Application identities do
not create or alter production topics.

### 7.2 Local, test, and production provisioning

`ReservationKafkaConfig` declares the source `NewTopic` only under the `local` profile, with three
partitions, replication one, delete cleanup, and seven-day retention. Inventory continues to
declare only its own DLT locally. A missing topic never falls back to broker auto-creation.

Kafka integration tests use `apache/kafka:4.3.1` through Testcontainers, disable automatic topic
creation, and declare an isolated three-partition source topic in test configuration. Tests supply
unique topic names through dynamic properties and do not depend on the common Compose broker.

Production has no `NewTopic` bean. Infrastructure provisioning creates the topic with the policy
in §7.1 before rollout and supplies bootstrap/security configuration externally.

The common Compose repository must add `KAFKA_BOOTSTRAP_SERVERS=rentflow-kafka:19092` and the
`local` profile to Reservation, and make Reservation depend on a healthy local Kafka broker. That
repository continues to own only broker topology and wiring; this repository owns the topic bean
and contract. This cross-repository wiring is required before the end-to-end local workflow is
complete.

## 8. Observability and health

### 8.1 Metrics

Micrometer exposes bounded metrics:

| Metric | Type | Labels/meaning |
| --- | --- | --- |
| `reservation.cancellation.outbox.publish.attempts` | Counter | `outcome=published|failed` |
| `reservation.cancellation.outbox.retries.scheduled` | Counter | No labels |
| `reservation.cancellation.outbox.pending` | Gauge | Current unpublished count |
| `reservation.cancellation.outbox.oldest.age` | Gauge | Oldest unpublished age in seconds; zero when empty |
| `reservation.cancellation.outbox.cleanup.deleted` | Counter | Rows deleted |
| `reservation.cancellation.outbox.cleanup.failures` | Counter | Failed cleanup runs |
| `reservation.cancellation.outbox.cleanup.duration` | Timer | Cleanup run duration |
| `reservation.cancellation.outbox.cleanup.backlog` | Gauge | Published rows currently eligible for cleanup |

The relay refreshes backlog gauges after every polling invocation using PostgreSQL-derived values;
the cleanup scheduler refreshes its backlog after each run. Metrics never label by event ID,
serial number, reservation/customer/order identifier, topic partition, exception class, or error
message.

### 8.2 Logging and alerts

A failed publication emits one warning with event ID, committed attempt count, bounded failure
code, and next-attempt time. It logs neither record key, serial number, JSON payload, exception
message, nor stack trace. The first later success emits one informational recovery message if the
row had failed before. Cleanup failure emits a bounded warning and is retried on the next schedule.

Operational alerting watches sustained nonzero pending count, oldest age, failed-attempt rate, and
cleanup backlog. Pending-age thresholds reflect the business release-latency objective; Kafka's
seven-day retention begins only after publication and is monitored through Inventory consumer lag.
Alert rules and routing live in deployment infrastructure; metric names and interpretation are
service-owned documentation.

### 8.3 Health semantics

Readiness remains `readinessState,db`; Kafka is not added. Liveness also remains unchanged. Kafka
unavailability must not remove a healthy database-capable instance from service or prevent it from
committing new cancellations to the outbox. Producer/admin fail-fast behavior is disabled, and the
relay reports broker failures through metrics and logs.

The local Compose dependency on a healthy broker is a developer startup convenience, not the
production readiness contract. The producer is otherwise lazy and cancellation processing does
not establish a Kafka connection.

## 9. Failure and edge-case matrix

| Case | Required result |
| --- | --- |
| Missing reservation | `404`; no reservation or outbox write |
| Malformed UUID | `400`; controller service is not invoked |
| Already `CANCELLED` | `204`; no new event and no timestamp sampling |
| Concurrent cancel calls | Row lock yields one transition/event; all committed observations return `204` |
| Event serialization or outbox insert fails | Whole cancellation transaction rolls back; safe `500` |
| Process dies before cancellation commit | Reservation and event both roll back |
| Process dies after cancellation commit | Pending row remains for another instance |
| Kafka/topic unavailable | Oldest row records failure/backoff; HTTP cancellation remains database-local |
| Topic is absent with auto-create disabled | Send fails and retries indefinitely; operator alert fires |
| Process dies before send | Row remains unchanged and eligible |
| Process dies or times out after broker acceptance | Row is retried with identical bytes; Inventory inbox suppresses duplicate effect |
| Broker acknowledges but outbox commit fails | Row is retried with identical bytes |
| Multiple relay instances run | One obtains the transaction advisory lock; others return immediately |
| Oldest row is backing off | Later rows are deliberately not published |
| Published row reaches 30 days | Bounded cleanup may delete it |
| Unpublished row becomes arbitrarily old | Cleanup retains it; backlog/age alerts escalate |
| Legacy delete follows committed cancellation | Outbox survives because there is no reservation foreign key |
| Legacy PUT/DELETE bypasses cancellation | No event; accepted temporary limitation |
| Inventory is down but Kafka accepts events | HTTP and publication can succeed; consumer catches up later |
| First delivery is stale after out-of-band reassignment | Inventory cannot identify ownership; accepted version-1 limitation requiring operator care |
| Source record expires before Inventory consumes it | Operator may safely republish retained outbox bytes within the 30-day window after investigation |

## 10. Security and data handling

The event contains only an opaque UUID, type/version constants, occurrence time, and Inventory
serial number. It excludes customer/order identifiers and request metadata. Payload text and record
keys never appear in application logs or metric labels. Exception messages and stack traces are
not persisted in the outbox.

Kafka bootstrap addresses and future authentication material are external configuration. No
credentials are committed to source, specifications, logs, or outbox rows. Production TLS, SASL,
ACLs, secret rotation, and broker hardening remain platform concerns.

## 11. Verification strategy

### 11.1 Unit tests without Spring

Unit tests cover:

- `HELD`/`CONFIRMED` transition versus `CANCELLED` no-op behavior;
- exact five-field compact JSON, UUID/type/version/time/serial values, field order, escaping, and
  4096-byte enforcement;
- identical persisted key/value use on every relay attempt;
- failed-attempt classification and exponential-backoff bounds, saturation, and jitter range;
- scheduler drain stopping on empty, ineligible, lock-busy, failure, count, and runtime limits; and
- entity delivery-state transitions without weakening assertions or sleeping.

Mockito is used for repositories, KafkaTemplate, metrics, and time/result collaborators where
needed. No unit test starts Spring or Docker.

### 11.2 PostgreSQL integration tests

PostgreSQL 18.4 Testcontainers tests cover:

- V3 table, column, constraint, partial-index, and schema ownership shape;
- invalid JSON/key/size/state combinations rejected by database constraints;
- eligible cancellation atomically persists one status change and one exact outbox row;
- missing and already-cancelled paths write nothing;
- forced rollback leaves both reservation and outbox unchanged;
- concurrent cancel calls produce one outbox row;
- advisory lock exclusion and oldest-row FIFO selection;
- pending/backlog queries use PostgreSQL time; and
- cleanup deletes only published rows older than 30 days in bounded chunks.

Tests remain isolated to the `reservation` schema and do not access Inventory tables.

### 11.3 Kafka integration tests

A real Kafka 4.3.1 Testcontainer with topic auto-creation disabled verifies:

- the source topic has three partitions, delete cleanup, and seven-day retention;
- the relay publishes the exact persisted UTF-8 key/value with no Java type headers;
- same serial keys select the same partition and retain relay order;
- broker acknowledgement marks the row published;
- broker failure/timeout leaves it unpublished with persisted backoff;
- a later successful retry preserves the event ID and bytes;
- duplicate publication after an ambiguous result is tolerated by contract; and
- a Kafka outage does not prevent an HTTP cancellation from committing locally.

Test configuration uses short timeouts/backoff and deterministic polling helpers rather than blind
sleep increases.

### 11.4 HTTP, OpenAPI, architecture, and final gate

HTTP integration tests cover `204` for both transition and no-op, `404`, malformed UUID `400`, an
empty response body, no required idempotency header/body, and unchanged legacy PUT/DELETE behavior.
OpenAPI tests verify the operation and eventual-consistency description. ArchUnit continues to
enforce the existing layers and package suffixes.

README/service documentation covers the endpoint, event/topic contract, outbox/inbox boundary,
at-least-once windows, FIFO blocking, retry defaults, cleanup retention, metrics, alerts, and
guarded manual republishing. The final gate is `mvn -B -ntp clean verify` with a real PostgreSQL and
Kafka where required.

## 12. Acceptance-criteria traceability

| Requirement | Design coverage |
| --- | --- |
| AC1.1 | §2.1–§2.2 |
| AC1.2 | §2.1–§2.3 |
| AC1.3–AC1.5 | §2.1–§2.2 |
| AC1.6 | §2.3 |
| AC1.7 | §2.2, §9 |
| AC2.1 | §2.2, §4.2 |
| AC2.2 | §4.1–§4.2 |
| AC2.3–AC2.6 | §3.1–§3.2, §4.2 |
| AC2.7 | §2.2–§2.4 |
| AC3.1–AC3.2 | §3.2, §5.3 |
| AC3.3 | §5.3 |
| AC3.4–AC3.5 | §5.3–§5.5 |
| AC3.6 | §2.2, §8.3, §9 |
| AC3.7 | §1.1, §5.5 |
| AC4.1 | §7.1–§7.2 |
| AC4.2 | §3.2, §5.2 |
| AC4.3 | §1.1, §2.2, §5.5 |
| AC4.4 | §1.1, §4.1, §7.1 |
| AC4.5 | §2.4 |
| AC4.6 | §6–§8, §11.4 |
