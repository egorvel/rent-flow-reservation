# Reservation Creation Requirements

Status: Requirements resolved for the synchronous terminal-ledger design.

## Context

The reservation service creates an atomic batch of `HELD` reservations for one customer and order.
Each item supplies a case-sensitive Inventory serial number and its own inclusive start and end
dates. PostgreSQL is the authority for the current UTC date and persisted timestamps.

An item may have only one active reservation. For creation, a local reservation is active when its
status is `HELD` or `CONFIRMED` and its `endDate` is the current PostgreSQL UTC date or later. The
periods do not merely need to overlap: any current or future active reservation blocks another
reservation for that serial number. `CANCELLED` and out-of-date reservations do not block.

After the local check passes, Reservation synchronously asks Inventory to atomically transition the
whole batch to `RESERVED`. Inventory owns serial-number validity and availability and is the
concurrency authority for competing reservation requests. Reservation creates its rows only after
Inventory returns its exact success response.

The endpoint requires a UUID v4 `Idempotency-Key`. Reservation uses the same compact terminal-ledger
pattern as Inventory: a transaction-scoped advisory lock serializes one key, and a single table
stores the request fingerprint, terminal HTTP status, complete response snapshot, database
completion time, and seven-day expiry. Matching retries load the response from this table. There
are no processing states, leases, persisted payloads, durable retry queues, recovery workers, or
reconciliation records.

Inventory transport failures and ambiguous protocol failures are not terminal business outcomes
and are not stored in the ledger. A client may retry them with the same key. All automatic
Inventory retries happen inside the incoming HTTP request.

## User stories with acceptance criteria

### US1 — Create an atomic reservation batch

As a reservation client, I want to reserve multiple items for one customer and order so that the
batch succeeds or fails as one operation.

- **AC1.1 (Event-driven):** When a valid request contains 1–100 unique items, no requested item has
  an active local reservation, Inventory reserves every item, and local persistence commits, the
  reservation service shall return `201 Created` with every created reservation in request order.
- **AC1.2 (Ubiquitous):** The reservation service shall create each item with a generated UUID,
  PostgreSQL-generated timestamp, `HELD` status, the common `customerId` and `orderId`, and the
  item's `serialNumber`, `startDate`, and `endDate`.
- **AC1.3 (Ubiquitous):** The reservation service shall require nonblank `customerId` and `orderId`
  values of at most 64 characters and an `items` array containing 1–100 non-null objects.
- **AC1.4 (Ubiquitous):** The reservation service shall require every item to contain a
  `serialNumber`, `startDate`, and `endDate`, and shall reject duplicate case-sensitive serial
  numbers within a batch.
- **AC1.5 (Event-driven):** When every start date is on or after the current PostgreSQL UTC date and
  every end date is on or after its start date, the reservation service shall accept the periods,
  including same-day periods.
- **AC1.6 (Unwanted):** If the request is invalid, then the reservation service shall return `400`
  with field violations, create no reservation, and make no Inventory request.
- **AC1.7 (Ubiquitous):** The reservation service shall persist either every reservation and its
  successful ledger outcome in one local transaction or none of them.
- **AC1.8 (Ubiquitous):** The reservation service shall not return partial success or a `Location`
  header for batch creation.

### US2 — Enforce one active reservation per item

As a rental operator, I want an item to have at most one current or future active reservation so
that it cannot be promised to two orders.

- **AC2.1 (Ubiquitous):** The reservation service shall treat a `HELD` or `CONFIRMED` reservation
  whose `endDate` is on or after the current PostgreSQL UTC date as active, regardless of its start
  date or overlap with the requested period.
- **AC2.2 (Unwanted):** If one or more requested serial numbers have active local reservations,
  then the reservation service shall return `409 ACTIVE_RESERVATION_EXISTS`, report every conflict
  in request order, store that terminal response, create no reservation, and make no Inventory
  request.
- **AC2.3 (Event-driven):** When a serial number has only `CANCELLED` reservations or out-of-date
  `HELD` or `CONFIRMED` reservations, the reservation service shall allow it to proceed to
  Inventory and shall leave those rows unchanged.
- **AC2.4 (Event-driven):** When different idempotency keys concurrently request the same item, the
  reservation service shall rely on Inventory's atomic transition so at most one batch returns
  `201` and the losing batch creates no reservation.
- **AC2.5 (Ubiquitous):** The reservation service shall perform the local active-reservation check
  once, before requesting Inventory.

### US3 — Coordinate synchronously with Inventory

As a reservation client, I want Inventory availability checked during my request so that the
response describes the completed synchronous attempt.

- **AC3.1 (Event-driven):** When local admission succeeds, the reservation service shall send one
  atomic Inventory batch requesting `RESERVED` for every serial number in request order and shall
  forward the public idempotency key unchanged.
- **AC3.2 (Ubiquitous):** The reservation service shall treat only Inventory's exact `204 No
  Content` response as authorization to create reservations.
- **AC3.3 (Unwanted):** If Inventory reports missing items, then the reservation service shall
  return and store `422 INVENTORY_ITEM_NOT_FOUND` with request-ordered failures and create no
  reservation.
- **AC3.4 (Unwanted):** If Inventory rejects an unavailable item, including a mixed missing and
  unavailable batch, then the reservation service shall return and store
  `409 INVENTORY_ITEM_UNAVAILABLE` with request-ordered failures and create no reservation.
- **AC3.5 (Unwanted):** If Inventory reports `IDEMPOTENCY_IN_PROGRESS`, then the reservation
  service shall return `409` with the same code and `Retry-After: 1`, create no reservation, and not
  store that transient response in the Reservation ledger.
- **AC3.6 (Unwanted):** If Inventory reports `IDEMPOTENCY_KEY_REUSED`, then the reservation service
  shall return and store `422 IDEMPOTENCY_KEY_REUSED` and create no reservation.
- **AC3.7 (Unwanted):** If Inventory rejects serial-number syntax, then the reservation service
  shall return and store `400 INVALID_INVENTORY_REFERENCE` with safe violations and create no
  reservation.
- **AC3.8 (Unwanted):** If Inventory remains unavailable after foreground retries, then the
  reservation service shall return `503 INVENTORY_SERVICE_UNAVAILABLE`, roll back the local
  transaction, store no outcome, and perform no later work unless a client sends another request.
- **AC3.9 (Unwanted):** If Inventory returns an unexpected status or malformed lifecycle response,
  then the reservation service shall return `502 INVENTORY_SERVICE_ERROR`, roll back the local
  transaction, and store no outcome.
- **AC3.10 (Ubiquitous):** The reservation service shall access Inventory only through its HTTP API
  and shall never read or modify Inventory-owned tables.

### US4 — Replay terminal outcomes

As an API consumer, I want terminal responses retained under an idempotency key so that retries do
not repeat completed business work.

- **AC4.1 (Unwanted):** If the request lacks exactly one canonical UUID v4 `Idempotency-Key`, then
  the reservation service shall return `400 VALIDATION_FAILED`, write no ledger row, and make no
  Inventory request.
- **AC4.2 (Event-driven):** When a new or expired key reaches command-level validation, the
  reservation service shall calculate a SHA-256 fingerprint from the validated common and ordered
  item fields and attempt execution under a transaction-scoped advisory lock derived from the key.
- **AC4.3 (Event-driven):** When a new attempt produces a terminal `201`, `400`, `409`, or `422`
  outcome, the reservation service shall atomically store its fingerprint, HTTP status, complete
  response snapshot, PostgreSQL completion timestamp, and expiry exactly seven days later.
- **AC4.4 (Event-driven):** When an unexpired key is retried with the same fingerprint, the
  reservation service shall load and replay the stored status and body without validation against
  the current date, a local availability query, an Inventory call, or new reservation writes.
- **AC4.5 (Unwanted):** If an unexpired key is reused with a different fingerprint, then the
  reservation service shall return `422 IDEMPOTENCY_KEY_REUSED` without changing the ledger,
  reservations, or Inventory.
- **AC4.6 (State-driven):** While another transaction holds the same execution lock, the
  reservation service shall return `409 IDEMPOTENCY_IN_PROGRESS` with `Retry-After: 1` without
  waiting, writing a ledger outcome, or contacting Inventory.
- **AC4.7 (Event-driven):** When a ledger row is expired, the reservation service shall treat the
  key as new and replace the row with the new terminal outcome after executing the request.
- **AC4.8 (Ubiquitous):** The reservation service shall attach `Idempotency-Replayed` and
  `Idempotency-Key-Expires-At` to new and replayed stored outcomes and shall not extend expiry on
  replay.
- **AC4.9 (Ubiquitous):** The reservation service shall derive completion, expiry, and cleanup time
  from PostgreSQL and shall retain no service-instance clock for business decisions.
- **AC4.10 (Ubiquitous):** The reservation service shall delete expired ledger rows in bounded
  cleanup chunks while leaving reservations and unexpired outcomes unchanged.

### US5 — Bound foreground resilience

As a service operator, I want short Inventory retries and no durable recovery loop so that transient
failures are tolerated without asynchronous creation behavior.

- **AC5.1 (Unwanted):** If an Inventory call fails through resource access or HTTP `502`, `503`, or
  `504`, then the reservation service shall make at most five total physical attempts inside the
  incoming request with the identical Inventory key and payload.
- **AC5.2 (Unwanted):** If another attempt remains, then the reservation service shall wait using
  nominal delays of 200, 400, 800, and 1600 milliseconds with 20-percent random jitter.
- **AC5.3 (Unwanted):** If Inventory returns another `5xx`, a terminal `4xx`, or an unexpected
  normal status, then the reservation service shall not retry it through Resilience4j.
- **AC5.4 (Ubiquitous):** The reservation service shall wrap the complete retry sequence in one
  Inventory circuit-breaker call.
- **AC5.5 (State-driven):** While the Inventory circuit is open, the reservation service shall make
  no HTTP attempt and shall return `503 INVENTORY_SERVICE_UNAVAILABLE`.
- **AC5.6 (Ubiquitous):** The reservation service shall have no reservation-creation scheduler,
  durable retry queue, recovery deadline, processing lease, or retry-attempt column.

### US6 — Publish and observe the contract

As an API consumer and operator, I want stable responses and documentation so that creation can be
used and diagnosed correctly.

- **AC6.1 (Ubiquitous):** The reservation service shall return creation failures as RFC 9457
  Problem Details with stable fields and request-ordered batch failure details where applicable.
- **AC6.2 (Ubiquitous):** The OpenAPI document shall describe the batch, idempotency headers,
  replay behavior, retry behavior, and all applicable creation response statuses.
- **AC6.3 (Ubiquitous):** The README and walkthrough shall describe terminal replay, five
  foreground attempts, cleanup, and the absence of background creation processing.
- **AC6.4 (Ubiquitous):** The reservation service shall expose bounded-label metrics for
  idempotency attempts, replay, mismatch, busy execution, cleanup, and Inventory resilience without
  identifiers or request values in metric labels.

## Out of scope

- Reservation replacement, cancellation, deletion, rental, return, and Inventory release.
- Background retry, durable recovery, compensation, and automatic reconciliation.
- Authentication and authorization.
- Changes to Inventory's transition API, status model, or owned database objects.
- Persisting malformed JSON, missing-header errors, or framework-level request-binding failures in
  the terminal ledger because no canonical business command exists for fingerprinting.

## Resolved questions

1. **Persistence model.** Use an Inventory-style terminal outcome table with key, fingerprint,
   HTTP status, JSON outcome, database timestamp, and seven-day expiry. See `design.md` §4.
2. **Replay source.** Load successful and failed terminal responses from the outcome table. See
   `design.md` §3.2.
3. **Concurrent same-key requests.** Return immediate `409 IDEMPOTENCY_IN_PROGRESS`. See
   `design.md` §3.1.
4. **Local availability timing.** Check once before Inventory. See `design.md` §2.2.
5. **Retries.** Retain five foreground attempts with exponential jittered waits. See `design.md`
   §5.
6. **Cleanup.** Retain bounded daily cleanup and the fields needed for expiry. See `design.md` §6.
7. **Migration strategy.** Replace the unshipped reservation-creation migration rather than add a
   compatibility migration. See `design.md` §4.1 and `tasks.md` T1.
