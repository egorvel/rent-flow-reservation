# Reservation Creation Design

Status: Approved for implementation.

## 1. Scope and structure

### 1.1 Design boundary

`POST /api/v1/reservations` creates one atomic batch of `HELD` reservations and coordinates one
synchronous Inventory status transition. Retrieval, listing, replacement, and deletion remain
unchanged. Inventory remains the owner of serial-number validity and item status; Reservation never
accesses the Inventory schema.

The design deliberately follows Inventory's terminal idempotency ledger rather than a workflow
engine. Reservation records completed business outcomes and replays them for seven days. It does
not persist work-in-progress state or schedule creation work.

### 1.2 Components

The runtime path uses these components:

```text
ReservationController
  -> ReservationCreationService
       -> ReservationCreationRequestRepository
       -> ReservationRepository
       -> InventoryGateway
            -> RestInventoryService
                 -> InventoryHttpClient
```

- `ReservationController` parses the key, maps the DTO to a command, maps the service result to
  HTTP, and adds idempotency headers.
- `ReservationCreationService` owns validation, idempotency, local availability, Inventory
  orchestration, reservation creation, outcome construction, and bounded creation metrics.
- `ReservationCreationRequestRepository` provides the terminal ledger, PostgreSQL time,
  transaction advisory locking, and cleanup queries.
- `ReservationRepository` provides the existing active-reservation lookup and persistence.
- `RestInventoryService` remains the HTTP adapter and owns Resilience4j retry and circuit-breaker
  composition.
- `ReservationCreationCleanupService` deletes expired terminal outcomes on the existing daily UTC
  schedule. It never invokes Inventory or creates reservations.

The redesign removes `ReservationCreationStoreService`, workflow preparation/result/state models,
lease exceptions, recovery services, creation settings/properties, and the separate creation
metrics facade. `ReservationCreationService.Result` is a small nested response record, matching
Inventory's service pattern.

## 2. Synchronous creation flow

### 2.1 Request validation and fingerprint

Spring/Jackson performs request binding and structural validation before invoking creation. Missing
or malformed JSON, unknown properties, missing fields, invalid date syntax, invalid collection
bounds, and missing/invalid keys return `400`, do not consume the key, and do not call Inventory.

The service validates rules that require the complete command: duplicate case-sensitive serial
numbers, supported year range, end before start, and start before the current PostgreSQL UTC date.
A command-level validation failure is a terminal `400 VALIDATION_FAILED` outcome and is stored.

The SHA-256 fingerprint is computed from a version marker and length-prefixed values for
`customerId`, `orderId`, item count, and each item's serial number and dates in request order.
JSON whitespace and object-property order do not affect it. Item order and string case do.

### 2.2 New attempt

`ReservationCreationService.create` is one local transaction:

1. Compute the fingerprint.
2. Acquire a transaction-scoped PostgreSQL advisory lock derived from the key with
   `pg_try_advisory_xact_lock`. Return immediate busy if unavailable.
3. Lock the exact ledger row, if present.
4. Replay or reject an unexpired row; otherwise continue as a new attempt.
5. Read PostgreSQL time and derive the UTC date without depending on the session timezone.
6. Validate command-level date and batch rules. Persist and return a terminal validation outcome
   when invalid.
7. Query active local reservations once for all unique requested serial numbers. Persist and
   return a terminal conflict when any are active.
8. Call Inventory once logically; Resilience4j may issue up to five physical attempts.
9. Map a known Inventory business rejection to a terminal Reservation outcome, store it, and
   return it.
10. On Inventory success, create all `HELD` reservations, flush them, build the response snapshot,
    store the `201` outcome, and commit.

The advisory lock and local transaction remain open during the Inventory call. This is an explicit
simplicity tradeoff inherited from the Inventory idempotency pattern. The configured Inventory
timeouts and bounded retries cap how long the transaction can remain open. No reservation row is
locked during that network wait because the local conflict lookup is read-only.

### 2.3 Local active-reservation rule

The repository lookup is:

```text
serial_number IN requested serials
AND status IN (HELD, CONFIRMED)
AND end_date >= current PostgreSQL UTC date
```

The service converts the result to a set and produces one failure for each matching request item in
request order. A future start is still active. An end date equal to today is still active.
`CANCELLED` rows and active-status rows ending before today are ignored.

There is no second local check after Inventory. Inventory's locked, atomic
`AVAILABLE -> RESERVED` transition serializes competing creation calls. This removes the former
post-claim reconciliation branch. Direct database writes and lifecycle operations that bypass
Inventory remain outside this feature.

### 2.4 Terminal and transient Inventory results

The adapter maps Inventory outcomes as follows:

| Inventory result | Reservation result | Stored |
| --- | --- | --- |
| Exact `204` | `201` with created reservations | Yes |
| `404 INVENTORY_ITEM_NOT_FOUND` | `422 INVENTORY_ITEM_NOT_FOUND` | Yes |
| `409 INVALID_INVENTORY_STATUS_TRANSITION` | `409 INVENTORY_ITEM_UNAVAILABLE` | Yes |
| `400 VALIDATION_FAILED` for serials | `400 INVALID_INVENTORY_REFERENCE` | Yes |
| `422 IDEMPOTENCY_KEY_REUSED` | `422 IDEMPOTENCY_KEY_REUSED` | Yes |
| `409 IDEMPOTENCY_IN_PROGRESS` | `409 IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1` | No |
| Exhausted transport/502/503/504 or open circuit | `503 INVENTORY_SERVICE_UNAVAILABLE` | No |
| Other status or malformed response | `502 INVENTORY_SERVICE_ERROR` | No |

Stored failures replay even if local or Inventory state later changes. Transient and ambiguous
errors roll back the transaction and leave no ledger row, so a client retry with the same key
executes again. Inventory's own durable key ensures that a retry can replay a committed upstream
transition after response loss.

## 3. Idempotency behavior

### 3.1 Execution lock

The execution lock ID is deterministically derived from the UUID using the existing SHA-256 helper.
`pg_try_advisory_xact_lock` returns immediately. Failure returns a
`ReservationCreationService.Result` with `409 IDEMPOTENCY_IN_PROGRESS`; the controller adds
`Retry-After: 1`.

The lock spans ledger inspection, local validation, Inventory coordination, local writes, outcome
storage, and commit. PostgreSQL releases it on commit, rollback, connection loss, or process death.
No lease or takeover rule is necessary.

### 3.2 Existing row and replay

After acquiring the advisory lock, the service loads the ledger row with a pessimistic write lock.
If its `expiresAt` is after current PostgreSQL time:

- a different fingerprint raises the mismatch variant and maps to
  `422 IDEMPOTENCY_KEY_REUSED`;
- the same fingerprint returns the deserialized outcome and original expiry with
  `replayed=true`.

Replay performs no current-date validation, active lookup, Inventory call, or reservation write.
The complete success or failure response is reconstructed from the stored outcome rather than from
current reservation rows.

If the row is expired, the service ignores its fingerprint and reuses the entity for the new
terminal outcome. Cleanup racing with execution is safe because both operations lock the row.

### 3.3 Atomic terminal persistence

Known business outcomes are ordinary return values, not transaction-escaping exceptions. The
service flushes reservation writes before sampling completion time and saving the successful
ledger row. Reservation rows and the `201` response therefore commit or roll back together.

For failure outcomes, the ledger row is the only write. Infrastructure and protocol exceptions
escape the transaction after being converted at the HTTP boundary, causing rollback and leaving no
terminal row. Busy and mismatch are also not stored; mismatch preserves the prior row unchanged.

The response snapshot stores generated reservation IDs, timestamps, statuses, and request order,
as well as all Problem Details fields and failure/violation arrays. Dynamic headers are derived
from the row's expiry and replay flag.

## 4. Persistence

### 4.1 Replacement V2 migration

The reservation-creation migration has not been released independently, so this redesign replaces
`V2__reservation_creation_requests.sql` and removes V3. V2 contains:

- the existing PostgreSQL default for `reservations.created_at`;
- `reservation.reservation_creation_requests`;
- one expiry index for cleanup;
- the active-reservation lookup index on `reservations`.

No migration creates or later removes workflow columns.

### 4.2 Terminal ledger

`reservation_creation_requests` contains exactly:

| Column | Type | Rule |
| --- | --- | --- |
| `idempotency_key` | `uuid` | Primary key |
| `fingerprint` | `varchar(64)` | Lowercase SHA-256 hex |
| `http_status` | `integer` | One of 201, 400, 409, 422 |
| `outcome` | `jsonb` | Object whose numeric `status` equals `http_status` |
| `recorded_at` | `timestamptz` | PostgreSQL completion time |
| `expires_at` | `timestamptz` | Exactly `recorded_at + 168 hours` |

The JPA entity has these fields only. Its `complete` method copies the outcome status, uses the
database timestamp, and calculates the seven-day expiry. There is no foreign key to reservations
because failure outcomes have no reservation rows and the stored response is an immutable snapshot.

### 4.3 Repository queries

Spring Data naming provides `findForUpdateByIdempotencyKey` under `PESSIMISTIC_WRITE`.
Native SQL remains only where method naming cannot express PostgreSQL behavior:

- `pg_try_advisory_xact_lock`;
- timezone-independent PostgreSQL UTC date/time sampling;
- chunked `DELETE ... FOR UPDATE SKIP LOCKED`;
- expired-row count.

The active lookup remains a Spring Data derived query.

## 5. Foreground Inventory resilience

`RestInventoryService` builds `CircuitBreaker(Retry(HTTP call))`. Retry handles
`ResourceAccessException` and HTTP 502, 503, or 504 only.

Configuration:

| Setting | Value |
| --- | --- |
| Total attempts | 5 |
| Initial nominal wait | 200 ms |
| Multiplier | 2 |
| Maximum nominal wait | 1600 ms |
| Random jitter | ±20% |
| Connect timeout | 500 ms |
| Read timeout | 1500 ms |

The nominal waits are 200, 400, 800, and 1600 ms. The public idempotency key and Inventory payload
remain identical across attempts. Decoded terminal 4xx responses, HTTP 500, and unexpected normal
responses are not retried.

## 6. Cleanup and metrics

`ReservationCreationCleanupService` runs daily at 03:00 UTC by default. It deletes expired rows in
chunks of 1000 ordered by expiry and key, using `FOR UPDATE SKIP LOCKED`, and stops after the
configured 60-second runtime budget. The scheduler touches only the terminal ledger.

The service records bounded values for idempotency attempt, replay, mismatch, and busy outcomes.
Cleanup records deleted rows, failure, duration, and expired backlog. Existing Resilience4j metrics
cover Inventory retry and circuit-breaker behavior. Keys, serial numbers, customers, orders, and
payloads never appear as metric labels.

## 7. Failure and concurrency cases

| Case | Result |
| --- | --- |
| Reservation process dies before commit | Local transaction and advisory lock roll back; same-key retry executes |
| Inventory commits but response is lost | Local transaction rolls back; same-key retry receives Inventory's stored result |
| Local reservation or ledger write fails after Inventory success | Local transaction rolls back; same-key retry replays Inventory and tries local commit again |
| Same key arrives concurrently | One transaction executes; the other receives immediate busy |
| Different keys target one item | Inventory serializes its item row; at most one transition succeeds |
| Stored failure is retried after conditions change | Original failure replays until expiry |
| Key is retried after expiry | Request executes as a new intent and replaces the row on terminal completion |
| Cleanup and replay race | Row lock determines order; replay never reads a partially deleted row |
| Retry crosses midnight without a Reservation ledger row | It is a new local attempt and current-date validation runs again |

The last case is the deliberate limitation of a terminal-only ledger. If Inventory committed just
before midnight and Reservation received no usable response, a retry after midnight can fail date
validation before consulting Inventory. Avoiding that edge would require durable in-progress state,
which this redesign explicitly excludes.

## 8. HTTP contract and documentation

The controller returns the stored outcome's status and body. Stored results include
`Idempotency-Replayed: false|true` and `Idempotency-Key-Expires-At`. Busy responses carry
`Retry-After: 1`. Transient 502/503 responses carry no terminal expiry headers.

The OpenAPI contract documents request bounds, active-reservation semantics, Inventory ownership,
stored replay, mismatch and busy precedence, all response statuses, and five foreground attempts.
README and the walkthrough explain that only the cleanup scheduler runs in the background; no
creation attempt continues after its HTTP response.

## 9. Verification

Unit tests cover command validation, fingerprinting, Inventory response decoding, and five-attempt
retry selection without sleeping. PostgreSQL integration tests cover migration shape and
constraints, advisory-lock concurrency, stored success/failure replay, expiry replacement, cleanup,
active-reservation boundaries, Inventory mappings, and atomic local rollback. OpenAPI and ArchUnit
tests cover the published contract and service boundaries. The final gate is
`mvn -B -ntp clean verify`.
