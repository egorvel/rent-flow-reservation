# Reservation Creation Requirements

Status: Requirements resolved; design and implementation tasks drafted.

## Context

The reservation service currently creates one `HELD` reservation at a time without checking
local availability or Inventory. This feature replaces that creation behavior with one atomic
batch operation. Customer and order identifiers are common to the batch, while each requested
item supplies its own serial number and rental period:

```json
{
  "customerId": "CUSTOMER-001",
  "orderId": "ORDER-001",
  "items": [
    {
      "serialNumber": "DRILL-001",
      "startDate": "2026-10-01",
      "endDate": "2026-10-03"
    },
    {
      "serialNumber": "MIXER-001",
      "startDate": "2026-10-01",
      "endDate": "2026-10-01"
    }
  ]
}
```

An inventory item can have only one active reservation. An existing reservation is active for
creation when it has `HELD` or `CONFIRMED` status and its inclusive `endDate` is the current UTC
date or later. This includes a future reservation whose `startDate` has not arrived. A `CANCELLED`
reservation or a `HELD` or `CONFIRMED` reservation whose `endDate` is before the current UTC date
does not block creation, and checking availability does not change the stored status. Dates do not
permit a second reservation while another active reservation exists. PostgreSQL is the production
time authority for this UTC date and for persisted creation and workflow timestamps, avoiding
different decisions when service-instance clocks drift or requests cross a UTC-date boundary.

After local request and availability checks succeed for the complete batch, Reservation uses
Inventory's atomic `PATCH /api/v1/inventory/status` operation to transition every requested item
from `AVAILABLE` to `RESERVED`. The
[Inventory Status Transition requirements](../../../rent-flow-inventory/.specs/inventory-status-transition/requirements.md)
are normative for serial-number validation and that upstream contract. In particular,
`AVAILABLE` is the only state eligible for a new reservation claim, competing claims are
serialized, the batch is atomic, and the endpoint provides durable idempotency.

The caller supplies one idempotency key for the complete creation intent. Reservation records the
intent durably and forwards the same key to Inventory on every attempt. This permits safe retry
and recovery when an Inventory response is lost or the Reservation process stops after Inventory
commits but before Reservation commits. A terminal success is returned only after Inventory has
reserved every item and Reservation has atomically persisted every requested reservation.

This feature supersedes the Service Skeleton requirements only for `POST /api/v1/reservations`,
creation-time validation, duplicate serial behavior, and creation-specific OpenAPI and README
content. Retrieval, listing, replacement, deletion, generic error handling, platform ownership,
health, and build requirements remain unchanged.

## User stories

### US1 - Create a reservation batch

As a reservation client, I want to create several reservations for one customer and order so that
the equipment is claimed as one operation.

- **AC1.1 (Event-driven):** When a new creation intent contains valid common fields and 1–100
  valid items with unique case-sensitive serial numbers, no requested item has an active local
  reservation, Inventory reserves the complete batch, and local persistence succeeds, the
  reservation service shall return `201 Created` with an array containing every created
  reservation in request order and without a `Location` header.
- **AC1.2 (Ubiquitous):** The reservation service shall create each batch item with a generated
  UUID `id`, an immutable PostgreSQL-generated UTC `timestamp`, `HELD` status, the batch's
  `customerId` and `orderId`, and that item's `serialNumber`, `startDate`, and `endDate`.
- **AC1.3 (Ubiquitous):** The reservation service shall require the batch `customerId` and
  `orderId` to be nonblank strings of at most 64 characters and shall keep their existing opaque,
  case-sensitive semantics without external validation.
- **AC1.4 (Ubiquitous):** The reservation service shall require `items` to contain 1–100 non-null
  objects with required `serialNumber`, `startDate`, and `endDate` properties and shall reject
  repeated case-sensitive serial-number strings within the batch; Inventory remains the authority
  for whether each serial-number value is valid.
- **AC1.5 (Event-driven):** When an item period starts on the current UTC date or a future date and
  ends on or after its start date, the reservation service shall accept the period, including a
  same-day period, subject to the other creation rules.
- **AC1.6 (Unwanted):** If a new creation intent contains a start date before the current UTC date,
  an end date before its start date, a year outside 0001–9999, an empty or over-100 item array, a
  null item, a repeated serial number, a missing or invalid common or date field, an unknown
  property, a value that cannot be deserialized or fails validation after the existing Jackson
  scalar conversion, or malformed JSON, then the reservation service shall return `400 Bad
  Request`, persist no creation intent or reservation, and make no Inventory request.
- **AC1.7 (Ubiquitous):** The reservation service shall persist either all reservations in a
  successful batch or none of them and shall never return a partial-success response.
- **AC1.8 (Ubiquitous):** The reservation service shall derive the current UTC date used for new
  intent validation and local active-reservation evaluation from PostgreSQL time with a conversion
  that is independent of the database session timezone.

### US2 - Enforce one active reservation per item

As a rental operator, I want an item to have only one current or future active reservation so that
equipment cannot be promised to different orders before the existing reservation becomes
outdated, is cancelled, or is fulfilled.

- **AC2.1 (Ubiquitous):** The reservation service shall treat any `HELD` or `CONFIRMED`
  reservation with the same case-sensitive serial number and an `endDate` on or after the current
  UTC date as an active reservation that blocks a new creation claim, independently of whether its
  `startDate` has arrived or its period overlaps the requested period.
- **AC2.2 (Unwanted):** If the pre-claim local evaluation finds active reservations for one or
  more requested serial numbers, then the reservation service shall return `409 Conflict`, create
  no reservations, make no Inventory request, and report all and only locally conflicting items
  in request order.
- **AC2.3 (Event-driven):** When a requested serial number has only `CANCELLED` reservations or
  `HELD` or `CONFIRMED` reservations whose `endDate` is before the current UTC date, the
  reservation service shall allow that item to proceed to the Inventory claim and shall leave the
  existing reservations unchanged.
- **AC2.4 (Event-driven):** When a `HELD` or `CONFIRMED` reservation has a future `startDate` or an
  `endDate` equal to the current UTC date, the reservation service shall treat it as active and
  block another creation claim for the same case-sensitive serial number.
- **AC2.5 (Unwanted):** If concurrent creation intents compete for the same locally available
  serial number, then the reservation service shall allow at most one intent to create an active
  reservation and shall reject every losing batch without persisting any of its reservations.
- **AC2.6 (Ubiquitous):** The reservation service shall complete validation and collect local
  active-reservation conflicts for the entire request before asking Inventory to mutate any item.

### US3 - Claim availability through Inventory

As a reservation client, I want Inventory to authorize every equipment claim so that Reservation
does not create records for missing or unavailable items.

- **AC3.1 (Event-driven):** When all requested items pass local validation and availability
  checks, the reservation service shall send one request-ordered Inventory batch containing each
  unique serial number with target status `RESERVED` and shall forward the creation intent's
  `Idempotency-Key` unchanged.
- **AC3.2 (Ubiquitous):** The reservation service shall treat only Inventory's exact terminal
  `204 No Content` success as authorization to persist the requested reservations.
- **AC3.3 (Unwanted):** If Inventory reports that every failed item is missing, then the
  reservation service shall return `422 Unprocessable Content` with code
  `INVENTORY_ITEM_NOT_FOUND`, preserve the request-ordered failed-item details, and persist no
  reservations.
- **AC3.4 (Unwanted):** If Inventory rejects any requested transition because an item is not
  `AVAILABLE`, including a mixed missing-and-unavailable batch, then the reservation service shall
  return `409 Conflict` with code `INVENTORY_ITEM_UNAVAILABLE`, preserve all request-ordered
  failed-item details, and persist no reservations.
- **AC3.5 (Unwanted):** If Inventory reports `IDEMPOTENCY_IN_PROGRESS`, then the reservation
  service shall return `409 Conflict` with the same stable code and `Retry-After` value, persist
  no reservations, and keep the creation intent resumable.
- **AC3.6 (Unwanted):** If Inventory reports `IDEMPOTENCY_KEY_REUSED`, then the reservation service
  shall return `422 Unprocessable Content` with the same stable code and shall persist no
  reservations.
- **AC3.7 (Unwanted):** If Inventory returns `400 Bad Request` because one or more serial numbers
  are invalid, then the reservation service shall return `400 Bad Request` with code
  `INVALID_INVENTORY_REFERENCE`, report the Inventory validation failures safely, and persist no
  reservations.
- **AC3.8 (Unwanted):** If Inventory returns another non-lifecycle `4xx` or a normal status other
  than exact `204`, then the reservation service shall return `502 Bad Gateway` with code
  `INVENTORY_SERVICE_ERROR` and shall persist no reservations.
- **AC3.9 (Unwanted):** If the Inventory call remains unavailable after the synchronous resilience
  policy finishes, then the reservation service shall return `503 Service Unavailable` with code
  `INVENTORY_SERVICE_UNAVAILABLE`, persist no reservations at that time, and retain the creation
  intent for safe retry and recovery.
- **AC3.10 (Ubiquitous):** The reservation service shall communicate with Inventory only through its
  HTTP API and shall not read or modify the Inventory-owned schema or tables directly.

### US4 - Retry and recover a creation intent safely

As an API consumer, I want durable idempotency and recovery so that an uncertain response cannot
create duplicate reservations or leave a successfully claimed item without its reservation.

- **AC4.1 (Unwanted):** If a creation request lacks exactly one canonical UUID v4
  `Idempotency-Key` header, then the reservation service shall return `400 Bad Request` with code
  `VALIDATION_FAILED` and an `Idempotency-Key` violation, persist no creation intent or
  reservation, and make no Inventory request.
- **AC4.2 (Event-driven):** When a structurally valid request uses a new or expired idempotency key
  and passes new-intent date validation, the reservation service shall durably record the complete
  creation intent before requesting an Inventory mutation.
- **AC4.3 (Ubiquitous):** The reservation service shall compare idempotent payloads using all
  validated common and item fields, preserve item order and string case, and ignore JSON
  whitespace and object-property order.
- **AC4.4 (Event-driven):** When the same unexpired key and equivalent payload retry a completed
  creation intent, the reservation service shall replay the original terminal status, body, and
  idempotency response headers without rechecking local availability, contacting Inventory, or
  creating additional reservations, including when an originally blocking reservation has since
  become outdated.
- **AC4.5 (Unwanted):** If an unexpired key for a recorded creation intent is reused with a
  different validated payload, then the reservation service shall return `422 Unprocessable
  Content` with code `IDEMPOTENCY_KEY_REUSED` and shall not change the original intent,
  reservations, or Inventory.
- **AC4.6 (State-driven):** While another execution owns the creation intent's processing lock,
  the reservation service shall return `409 Conflict` with code `IDEMPOTENCY_IN_PROGRESS` and
  `Retry-After: 1` without invoking Inventory or mutating reservations.
- **AC4.7 (Event-driven):** When execution stops or loses an Inventory response after Inventory may
  have committed but before local completion, the reservation service shall resume the recorded
  intent with the same Inventory idempotency key and payload after retry or application restart
  and shall create each reservation at most once.
- **AC4.8 (State-driven):** While a recorded creation intent is unfinished and remains within the
  safe Inventory idempotency-recovery period, the reservation service shall recover it without
  relying solely on another client request and shall never issue a blind compensating transition
  from `RESERVED` to `AVAILABLE`.
- **AC4.9 (Event-driven):** When an originally valid unfinished or completed intent is resumed or
  replayed after its requested start date has passed, the reservation service shall continue or
  replay that intent rather than reject it as a new historical request.
- **AC4.10 (Ubiquitous):** The reservation service shall retain terminal replay records for at
  least seven days from PostgreSQL-recorded completion, expose their expiry through
  `Idempotency-Key-Expires-At`, identify replays through `Idempotency-Replayed`, and never delete
  unfinished intents through terminal-record cleanup.
- **AC4.11 (Ubiquitous):** The reservation service shall atomically persist the created
  reservations and terminal success record so that a replay returns the same generated IDs,
  timestamps, status, and response ordering.
- **AC4.12 (Unwanted):** If an unfinished creation intent reaches the conservative seven-day
  Inventory idempotency-recovery deadline without a terminal outcome, then the reservation
  service shall stop automatic Inventory mutations for that intent, retain it for manual
  reconciliation, return `503 Service Unavailable` with code
  `CREATION_RECONCILIATION_REQUIRED` on a same-key retry, and emit a bounded metric and sanitized
  log without request identifiers.
- **AC4.13 (Unwanted):** If an active local reservation appears after pre-claim admission and
  Inventory has already authorized the batch, then the reservation service shall create no second
  active reservation, shall not issue a blind Inventory release, and shall retain the intent for
  manual reconciliation with `503 CREATION_RECONCILIATION_REQUIRED`.
- **AC4.14 (Ubiquitous):** The reservation service shall derive workflow recording and update
  times, lease boundaries, retry eligibility, recovery deadlines, terminal expiry, and cleanup
  eligibility from PostgreSQL time rather than a service-instance wall clock.

### US5 - Limit Inventory failures

As a service operator, I want bounded retries and circuit breaking around Inventory so that
temporary upstream failures are tolerated without uncontrolled duplicate mutations or traffic.

- **AC5.1 (Unwanted):** If the first Inventory attempt for one execution fails because of resource
  access or with `502`, `503`, or `504`, then the reservation service shall make exactly one
  delayed retry with the same idempotency key and identical Inventory payload before translating
  the synchronous result.
- **AC5.2 (Unwanted):** If an Inventory attempt returns another `5xx`, any terminal `4xx`, or an
  unexpected normal status, then the reservation service shall not retry that outcome within the
  synchronous attempt.
- **AC5.3 (Ubiquitous):** The reservation service shall present the complete synchronous retry
  sequence to the Inventory circuit breaker as one logical call and shall count only
  resource-access and Inventory `5xx` failures against that circuit.
- **AC5.4 (State-driven):** While the Inventory circuit is open, the reservation service shall
  avoid contacting Inventory, return the creation intent as temporarily unavailable, and retain
  unfinished work for later recovery.
- **AC5.5 (Ubiquitous):** The reservation service shall expose bounded-label metrics for Inventory
  retry and circuit-breaker outcomes and for creation attempts, replays, conflicts, resumptions,
  completions, and recovery failures without using idempotency keys, customer IDs, order IDs, or
  serial numbers as metric labels.

### US6 - Publish a predictable creation contract

As an API consumer, I want the batch creation and failure contracts documented so that I can
submit, retry, and diagnose a complete order safely.

- **AC6.1 (Ubiquitous):** The reservation service shall return creation-specific errors as RFC
  9457 Problem Details with stable `type`, `title`, `status`, `detail`, `instance`, and `code`
  fields; batch business conflicts shall also contain request-ordered `failedItems` with
  zero-based `index`, `serialNumber`, `code`, and safe `message` fields.
- **AC6.2 (Ubiquitous):** The OpenAPI contract shall document the creation envelope, common and
  item validation, 1–100 item bounds, unique serial-number rule, inclusive future-date rule,
  success array, omitted `Location`, required idempotency header, idempotency response headers,
  and applicable `201`, `400`, `406`, `409`, `415`, `422`, `500`, `502`, and `503` responses.
- **AC6.3 (Ubiquitous):** The repository README shall include executable batch-creation and
  same-key retry examples and shall explain that a `503` result is indeterminate and must be
  reconciled with the same idempotency key rather than a new creation intent.
- **AC6.4 (Event-driven):** When a client invokes reservation creation without authentication
  credentials, the reservation service shall process the request according to the functional
  rules in this specification.

## Out of scope

- Allowing overlapping or non-overlapping future active reservations for the same serial number.
- Using period overlap to decide local availability; an existing reservation's status and
  inclusive `endDate` determine whether it is active for creation.
- Validating Customer or Order existence in another service.
- Confirming, cancelling, expiring, or fulfilling reservations, including the corresponding
  Inventory transitions to `AVAILABLE` or `RENTED`.
- Changing the existing GET, list, PUT, or DELETE reservation contracts or applying new status
  transition guards to PUT.
- Automatically compensating a successfully claimed Inventory item back to `AVAILABLE`; unfinished
  creation is completed through idempotent recovery.
- Distributed transactions or two-phase commit across Reservation and Inventory.
- Direct cross-schema access, cross-service foreign keys, messaging, or event publication.
- Authentication, authorization, user identity, roles, or permissions.
- Changing Inventory's lifecycle matrix, history, idempotency retention, or cleanup behavior.
- Providing an administrative API, dashboard, or automatic decision for intents that require
  manual reconciliation after their safe recovery deadline.

## Resolved questions

1. **Request shape:** Creation uses an envelope with top-level `customerId` and `orderId` and an
   `items` array whose entries contain `serialNumber`, `startDate`, and `endDate`. See AC1.1–AC1.4
   and `design.md` §2.1.
2. **Batch bounds and duplicates:** A batch contains 1–100 non-null items and rejects repeated
   case-sensitive serial numbers. See AC1.4, AC1.6, and `design.md` §2.1.
3. **Date policy:** Dates are inclusive; same-day periods are valid; a new intent cannot start
   before the current UTC date obtained from PostgreSQL. See AC1.5–AC1.6, AC1.8, AC4.9, and
   `design.md` §2.1, §3.1, and §4.4.
4. **Atomic conflicts:** Any local active-reservation conflict rejects the entire batch with
   `409 Conflict` and request-ordered failure details. See AC2.2, AC6.1, and `design.md` §3.2 and
   §7.1.
5. **Success response:** Successful creation returns `201 Created` with a request-ordered array and
   no `Location` header. See AC1.1 and `design.md` §2.3.
6. **Availability model:** An item can have only one current or future active reservation, even
   when proposed periods do not overlap; Inventory's serialized `AVAILABLE` to `RESERVED`
   transition is the final claim gate. See AC2.1–AC2.6, AC3.1–AC3.4, and `design.md` §3.2–§3.3 and
   §5.
7. **Failure recovery:** Creation requires end-to-end durable idempotency, forwards the same key to
   Inventory, persists local intent before the remote mutation, and resumes interrupted work
   without blind compensation. See AC4.1–AC4.14 and `design.md` §3.4 and §6.
8. **Active-reservation boundary:** `HELD` and `CONFIRMED` reservations block creation when their
   inclusive `endDate` is the current UTC date or later, including before a future `startDate`.
   Reservations ending before the current UTC date and all `CANCELLED` reservations are ignored
   without mutation. A recorded idempotent outcome remains unchanged when this date boundary later
   passes. See AC2.1–AC2.4, AC4.4, and `design.md` §3.1–§3.2.
9. **Replacement scope:** The existing PUT replacement endpoint remains unchanged in this feature;
   the one-active-reservation guarantee applies to competing creation intents. A detected
   post-claim local conflict is retained for manual reconciliation rather than inserted or
   compensated. See AC4.13 and `design.md` §1.1, §3.2, and §3.3.
10. **Time authority:** PostgreSQL supplies the one captured UTC date for a new intent, generated
    reservation timestamps, and every persisted workflow time boundary; the UTC-date conversion
    does not depend on the database session timezone. Unit tests mock the database-time boundary,
    while PostgreSQL integration tests verify its SQL behavior. See AC1.2, AC1.8, AC4.10, AC4.14,
    and `design.md` §3.1, §4.1, §4.4, §6, and §10.
11. **Durable workflow:** One Reservation-owned workflow record stores the normalized request,
    fingerprint, state, outcome, lease, and timing data needed for replay and recovery. See
    `design.md` §4.
12. **Processing coordination:** Short database-backed leases coordinate foreground and recovery
    executions without holding a database transaction across the Inventory call. See `design.md`
    §3.4 and §6.1.
13. **Automatic recovery:** Recovery scans every 30 seconds by default, processes at most 100 due
    intents per run, and uses a 30-second lease; all values are configurable. See `design.md`
    §6.3.
14. **Recovery horizon:** An unresolved intent stops automatic Inventory calls at a conservative
    seven-day deadline and remains available for manual reconciliation. See AC4.12 and `design.md`
    §6.4.
15. **Inventory resilience:** The synchronous HTTP client, retry selection, jitter, and circuit
    breaker use Pricing's established defaults. See `design.md` §5.1 and §5.3–§5.4.
16. **Task granularity:** Implementation is split into nine safe, reviewable commits from database
    foundation through end-to-end acceptance. See `tasks.md` §2–§3.
17. **Persistence foundation:** V2 schema changes, database time, generated Reservation timestamps,
    and workflow persistence form the first increment. See `tasks.md` T1.
18. **API cutover:** No external cutover order was required; the plan keeps the existing POST
    operational until the completed workflow is connected in T8 so earlier commits remain
    deployable. See `tasks.md` §1 and T8.
19. **Scheduler decomposition:** Recovery/reconciliation and terminal cleanup/observability are
    separate commits because their mutation rules and verification differ. See `tasks.md` T6–T7.
20. **Verification cadence:** Every task runs the full Maven verification; the isolated container
    smoke test runs in final acceptance. See `tasks.md` §1 and T9.
21. **Fault verification:** Fault injection and focused concurrency tests accompany the task that
    introduces each boundary, while final acceptance covers cross-cutting restart and container
    behavior. See `tasks.md` §1, T4–T6, and T9.
