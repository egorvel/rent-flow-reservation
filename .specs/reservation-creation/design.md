# Reservation Creation Design

Current API ownership and authorization are defined by [security](../security/design.md) §3–§5.
Its token-derived immutable owner, strict owner-input rejection and caller-scoped replay supersede
this feature's original pre-security assumptions. Historical delivery/acceptance notes below are
not instructions to restore anonymous access or older schemas. Verification uses the linked
current contracts in place of superseded historical assertions; earlier dated reports remain unchanged.

Status: Implemented; ownership and idempotency descriptions aligned with the current security API.

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
- `ReservationCreationOutcome` owns its `Snapshot`, `Failure`, and `Violation` value records; their
  persisted JSON fields and ordering remain unchanged.
- `InventoryGateway` owns its protocol and service-unavailable exceptions as part of the gateway
  contract, while `RestInventoryService` translates HTTP failures into those types.
- `RestInventoryService` remains the HTTP adapter and owns Resilience4j retry and circuit-breaker
  composition.
- `ReservationCleanupScheduledService` and `ReservationCleanupService` delete expired terminal
  outcomes on the existing daily UTC schedule. They never invoke Inventory or create reservations.

The redesign removes `ReservationCreationStoreService`, workflow preparation/result/state models,
lease exceptions, recovery services, creation settings/properties, and the separate creation
metrics facade. `ReservationCreationService.Result` is a small nested response record, matching
Inventory's service pattern.

## 2. Synchronous creation flow

### 2.1 Request validation and fingerprint

POST accepts `CreateReservationsRequest` with only `orderId` and `items`. It has no customer owner
input. The authorized service obtains a CustomerIdentity from verified `iss`/`sub`, assigns the subject
as response-only `customerId`, and persists the issuer alongside it. Supplied `customerId` or
`customerIssuer` is rejected with `400` before service/ledger/Inventory work. The subject remains an
opaque string, without the former 64-character business-ID limit or UUID parsing. Staff also creates
for itself; no mapping or Keycloak lookup is needed.

Spring/Jackson performs request binding and structural validation before invoking creation. Missing
or malformed JSON, unknown properties, missing fields, invalid date syntax, invalid collection
bounds, and missing/invalid keys return `400`, do not consume the key, and do not call Inventory.

The service validates rules that require the complete command: duplicate case-sensitive serial
numbers, supported year range, end before start, and start before the current PostgreSQL UTC date.
A command-level validation failure is a terminal `400 VALIDATION_FAILED` outcome and is stored.

The SHA-256 payload fingerprint is computed from `reservation-creation-v2`, length-prefixed
`orderId`, item count, and each item's serial number and dates in request order. Ownership is bound
separately by the issuer/subject caller scope, not included again as a payload field.
JSON whitespace and object-property order do not affect it. Item order and string case do.

### 2.2 New attempt

`ReservationCreationService.create` is one local transaction:

1. Enforce current create permission, read the verified issuer/subject owner, derive the caller
   scope and compute the owner-free payload fingerprint.
2. Acquire a transaction-scoped PostgreSQL advisory lock derived from the caller scope and external key with
   `pg_try_advisory_xact_lock`. Return immediate busy if unavailable.
3. Lock the exact composite (caller_scope, idempotency_key) ledger row, if present.
4. Recheck stored target subject, reject fingerprint mismatches and verify successful snapshot
   ownership before replaying an unexpired row; otherwise continue as a new attempt.
5. Read PostgreSQL time and derive the UTC date without depending on the session timezone.
6. Validate command-level date and batch rules. Persist and return a terminal validation outcome
   when invalid.
7. Query active local reservations once for all unique requested serial numbers. Persist and
   return a terminal conflict when any are active.
8. Call Inventory once logically with the derived downstream UUID; Resilience4j may issue up to five physical attempts.
9. Map a known Inventory business rejection to a terminal Reservation outcome, store it, and
   return it.
10. On Inventory success, create all `HELD` reservations under the verified issuer/subject owner, flush them, build the response snapshot,
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

The ledger identity is `(caller_scope, idempotency_key)`. The scope hashes the verified issuer and
subject; the execution lock and Inventory UUID additionally include the external key, using the
exact length-prefixed SHA-256 tuples in [security design](../security/design.md) §5.1. Equal payload
fingerprints and external UUIDs from different subjects never share ledger records or downstream keys.
`pg_try_advisory_xact_lock` returns immediately. Failure returns a
`ReservationCreationService.Result` with `409 IDEMPOTENCY_IN_PROGRESS`; the controller adds
`Retry-After: 1`.

The lock spans ledger inspection, local validation, Inventory coordination, local writes, outcome
storage, and commit. PostgreSQL releases it on commit, rollback, connection loss, or process death.
No lease or takeover rule is necessary.

### 3.2 Existing row and replay

After acquiring the advisory lock, the service loads the ledger row with a pessimistic write lock.
If its `expiresAt` is after current PostgreSQL time, recheck current `reservation:create` and the
stored target subject; successful replay also verifies that every saved ID still exists under the
same issuer/subject. Deleted or foreign success rows yield concealed `404` without replay headers,
including for staff. Subject mismatch yields generic `403`. Once authorized:

- a different fingerprint raises the mismatch variant and maps to
  `422 IDEMPOTENCY_KEY_REUSED`;
- the same fingerprint returns the deserialized outcome and original expiry with
  `replayed=true`.

Replay performs no current-date validation, active lookup, Inventory call, or reservation write.
The complete success or failure response is reconstructed from the stored outcome rather than from
current reservation rows; the ownership query is an authorization check, not a reconstruction of
the response. Cancelled but still-owned rows replay their original creation snapshot.

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

### 4.1 Migration history and current schema

V2 introduced the terminal ledger, created_at default, expiry index and active-reservation index.
The old unshipped workflow-replacement plan is completed history, not an instruction to rewrite
V2 or remove today's V3 cancellation migration. The current consolidated security V6 upgrades V5
as specified in [security design](../security/design.md) §5.2: one authorized development reset,
issuer/subject ownership and composite ledger keys, without an intermediate mapping table.
Shipped V1–V5 remain unchanged. Reapplying the current migration set preserves post-cutover rows.

### 4.2 Terminal ledger

`reservation_creation_requests` contains exactly:

| Column | Type | Rule |
| --- | --- | --- |
| `caller_scope` | `varchar(67)` | Verified issuer/subject hash; part of the primary key |
| `idempotency_key` | `uuid` | External UUID; primary key together with caller_scope |
| `target_customer_id` | `text` | Accepted authenticated subject; nonblank |
| `fingerprint` | `varchar(64)` | Lowercase SHA-256 hex |
| `http_status` | `integer` | One of 201, 400, 409, 422 |
| `outcome` | `jsonb` | Object whose numeric `status` equals `http_status` |
| `recorded_at` | `timestamptz` | PostgreSQL completion time |
| `expires_at` | `timestamptz` | Exactly `recorded_at + 168 hours` |

The JPA entity has these fields only. Its `complete` method copies the outcome status, uses the
database timestamp, and calculates the seven-day expiry. There is no foreign key to reservations
because failure outcomes have no reservation rows and the stored response is an immutable snapshot.

### 4.3 Repository queries

Spring Data naming provides `findForUpdateById` with a `ReservationCreationRequestId` composite
identifier under `PESSIMISTIC_WRITE`; no UUID-only lookup is used.
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

The nominal waits are 200, 400, 800, and 1600 ms. The derived downstream Inventory key and payload remain identical across attempts; the public
key remains scoped to the authenticated issuer/subject at the Reservation boundary. Decoded terminal 4xx responses, HTTP 500, and unexpected normal
responses are not retried.

## 6. Cleanup and metrics

`ReservationCleanupScheduledService` runs creation-ledger cleanup daily at 03:00 UTC by default.
It delegates each chunk to `ReservationCleanupService` in a new transaction, deleting expired rows
in chunks of 1000 ordered by expiry, caller scope and key, matching both key components, with `FOR UPDATE SKIP LOCKED`, and stops after the
configured 60-second runtime budget. This entrypoint touches only the terminal ledger.

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
| Same caller scope and external key arrive concurrently | One transaction executes; the other receives immediate busy |
| Different scoped keys target one item | Inventory serializes its item row; at most one transition succeeds |
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
token-derived response-only ownership, caller-scoped keys, authorized stored replay, mismatch and busy precedence, all response statuses, and five foreground attempts.
README and the walkthrough explain that only the cleanup scheduler runs in the background; no
creation attempt continues after its HTTP response.

## 9. Verification

Unit tests cover command validation, fingerprinting, Inventory response decoding, and five-attempt
retry selection without sleeping. PostgreSQL integration tests cover migration shape and
constraints, advisory-lock concurrency, stored success/failure replay, expiry replacement, cleanup,
active-reservation boundaries, Inventory mappings, and atomic local rollback. OpenAPI and ArchUnit
tests cover the published contract and service boundaries. Signed-token security tests additionally
prove request owner fields are rejected, new identities need no provisioning, issuer/subject-scoped
keys remain separate, and staff cannot replay a deleted success. The final gate is
`mvn -B -ntp clean verify`.
