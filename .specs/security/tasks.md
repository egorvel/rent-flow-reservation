# Reservation HTTP security tasks

Status: Identity/API revision approved on 2026-11-24; implementation evidence is recorded separately; Common C1 pending.
Historical baseline evidence: [implementation acceptance](implementation-acceptance.md).

Six identity/API decisions were clarified and approved before this revision. Each task is one safe
commit. The user authorized specs, self-evaluation and implementation, including discarding development
reservation/outbox/replay records. Previous 2026-11-19 verification covers only the earlier mapping
implementation. Leave source changes reviewable; no external Common changes or live environment reset.

Order: T1 -> T2 -> T3. C1 depends on T1 and T2 and can run alongside T3 once separately
authorized. Local implementation acceptance requires T3; Phase 4 combined acceptance also requires
C1. T1 introduces persistence, inbound authorization, ownership and outbound credentials in one
coherent secured cutover, without an unauthenticated intermediate compatibility path. Deploy using
the stop/drain/migration/deploy sequence in `design.md` §11.4.

Every Reservation implementation task requires `mvn -B -ntp clean verify`, BUILD SUCCESS and no skipped checks.
If Spotless fails, run mvn spotless:apply and rerun the full lifecycle. Runtime/container changes
require scripts/container-smoke-test.sh and its existing success message. These are future
acceptance conditions; actual implementation results are recorded separately from the earlier
unchanged-code planning build.

## T1 — Enforce HTTP ownership and authenticate Inventory in one cutover

**Commit:** feat: secure reservation operations and inventory claims

**Depends on:** This agreed specification and a Reservation implementation request. Common's
existing machine grant is available; isolated fixtures do not require the future human-policy task.

**Refs.** `requirements.md` AC1.1, AC1.2, AC1.3, AC1.4, AC2.1, AC2.2, AC2.3, AC2.4, AC2.5,
AC2.6, AC3.1, AC3.2, AC3.3, AC3.4, AC4.1, AC4.2, AC4.3, AC4.4, AC4.5, AC5.1, AC5.2, AC5.3,
AC5.4, AC6.1, AC6.2, AC6.3, AC6.4, AC6.5, AC7.3, AC7.4, AC8.1, AC8.2;
`design.md` §1, §2, §3, §4, §5, §6, §7, §8, §9, §11.1, §11.2, §11.4.

**Scope.** Add managed security support, replace bans with narrow architecture rules, implement
typed policy/method security, token-derived immutable ownership, owner-filtered queries, scoped key
derivation/replay authorization and safe listener-aware chains. Consolidate security into one authorized V6 migration with
development-data reset, no intermediate mapping table, issuer/subject columns, composite creation IDs and scoped cleanup/monitoring. All caller scopes derive
from authenticated issuer/subject; no temporary unauthenticated scope or compatibility path is
introduced. Add dedicated OAuth client, protected file delivery, failure/resilience mapping and
safe telemetry. Adapt existing
integration tests and smoke in this same commit so enforcement has no broken verification path.
Docs stay disabled until T2. No sibling edits or running-realm changes.

**DoD.**

- Migration tests apply V1–V5, seed reservation/replay/outbox rows, then apply exactly one V6:
  all development reservation/replay/outbox rows disappear, no mapping table is created, required
  nonblank issuer/subject text columns and owner index exist, and other schemas are unchanged.
  Shipped V1–V5 are unchanged; the prior security V6/V7 pair is consolidated by explicit request. Reapplying Flyway preserves new reservations and replay records.
- Ownership persistence tests prove exact issuer/subject matching for get/page/count/locked cancel,
  including equal subjects under different issuers, case sensitivity, nonblank constraints and no
  mapping table. New permitted identities work without registration or a provider lookup.
- Persistence tests insert equal UUIDs under two p1: scopes with different target customers,
  assert independent lookup and row locking, correct embedded-ID equality and preserved TTL/outcome
  constraints. No legacy value or UUID-only repository lookup remains.
- Cleanup/monitoring tests place one expired and one unexpired equal-UUID record in different
  scopes: only the expired composite row is deleted, counts include both appropriately and existing
  chunk, SKIP LOCKED, transaction and time-budget behavior passes.
- Signed-token HTTP assertions cover every §2 validation row: supported token accepts; wrong
  signature/algorithm/issuer/audience/type/required claims/timestamp/own-role shape reject safely.
  Test case-insensitive JWT, scalar/array aud including malformed members, numeric-sub decoding,
  old unexpired iat, 30-second boundaries, duplicate/combined headers and ignored query/form tokens.
  Failed authentication asserts zero reservation/ledger/Inventory/token interactions.
- Startup tests accept valid configuration with unreachable identity/token/resource servers;
  missing/invalid issuer/JWKS/token URLs, secret, registration/grant/authentication and unsafe
  destination fail safely. JWKS tests prove configured fetch timeouts, bounded unknown-key denial,
  cached-key outage acceptance, key overlap/refresh and recovery without health dependence.
- Matrix tests cover all six routes, eight independent permissions, approved clients and accidental
  overgrants. CUSTOMER own operations work; STAFF any-read/list/cancel and manage work; staff creation requires create and assigns the caller.
  Removed create:any alone is denied before parsing/data access.
  Manage alone grants only PUT/DELETE. All machine/other-client business access, HEAD/OPTIONS and
  unknown routes are denied before resource access; malformed forbidden bodies return 403.
  Actual business service proxies enforce capabilities in servlet and non-web contexts.
- Signed ownership tests prove POST derives customerId from sub and persists iss, rejects customerId
  and customerIssuer inputs with 400 before locking/data access, ignores foreign token claims/headers,
  and returns read-only customerId without issuer. Foreign/missing get/cancel yield identical 404.
  New-user listing is empty without registration; equal orders and unsupported filters never widen
  access. Collection content/totals remain scoped for empty/overflow pages and all allowed sorts.
- Staff PUT changes the five mutable fields, preserves original issuer/subject, timestamp/deadline
  and emits no release event; supplied owner fields return 400. DELETE remains permanent without
  release. Owner cancellation checks already-cancelled rows too. Real database locked-delete races
  prove a waiting customer cannot mutate a deleted row. Existing cancellation/expiration atomicity
  and business validation/status outcomes remain intact. Policy owns permission/scope decisions;
  CustomerIdentityService only extracts verified identity, with no permission or database logic.
- Fixed tuple/hash/UUID vectors prove subject/issuer separation, UUID-v4 parser compatibility,
  renewal/approved-client stability and correct downstream derivation. Two subjects use the same
  external UUID against the actual dedicated HTTP client: observed Inventory keys differ; retry
  and application restart retain each derived key. No request-provided or fixed fallback scope remains.
- Scoped replay tests cover original snapshot, fingerprint mismatch, busy/Retry-After, seven-day
  TTL and expiry reuse, reduced/lost create permission, target mismatch and deleted success rows.
  Every caller including staff needs current ownership for successful replay; deleted rows yield
  404 without partial snapshots/replay headers. Cancelled-but-still-owned rows retain the creation
  snapshot. Failed outcomes remain caller-scoped and target-authorized. Existing hash vectors remain.
- The actual HTTP service client/manager sends only the fixed reservation-service identity,
  all-RESERVED payload and derived key. Sequential users and no-request gateway calls reuse tokens
  and renew at the 30-second boundary. No human bearer/X-User headers or global bearer attachment,
  refresh flow, servlet session or redirect occurs. Token transport is bounded to 2-second limits.
- Table-driven acquisition/resource tests cover every §7 failure row and exact generic status/code,
  no customer challenge, no replay headers, no saved ledger/reservation on auth/acquisition failure
  and no Inventory call when acquisition fails. Inventory 401 evicts even without challenge and
  fails once; only a later explicit call reacquires. A 403 retains the token and never renews.
  Token/auth failures do not enter Inventory retry/circuit accounting or business-error decoding.
- Existing resource resilience tests retain at most five eligible attempts with the same key,
  200ms–1600ms exponential nominal backoff/jitter and open-circuit no token/resource attempt.
  Rollback and post-Inventory-commit retry tests prove the same key recovers the prior claim;
  no test claims remote rollback/compensation. Local get/list/cancel/replace/delete use no token
  endpoint, including provider outage with a valid known-key incoming token.
- Both actual listeners prove minimal probes, private metrics, no health details/components,
  database readiness behavior and no identity/Inventory/Kafka health dependency. Spoofed headers
  cannot select management. Statelessness/no-cookie/no-redirect/Basic/form/CORS assertions pass.
  Public metadata tests cover native root/nested/trailing/encoded paths, exact four fields,
  no query/scopes/credential fields and identical documents for every bearer variation during
  outage/docs-off. Other well-known/non-GET and wrong-listener paths remain denied.
- Expiration, relay, cleanup and monitoring run without SecurityContext, retain existing bounds,
  outbox rollback/recovery, at-least-once and manual/expiry race tests. Outbox JSON/Kafka headers
  and persisted rows contain no tokens, client secrets or introduced customer/subject credentials.
- Filter/method errors share exact safe ProblemResponse/challenge behavior even with hostile
  Accept; broad advice does not turn security denials into 500. Synthetic bearer/secret/subject/
  provider-body markers are absent from responses, captured ECS logs, exported spans and metric
  labels. Only §8's bounded counters/categories are emitted; existing safe tracing is preserved.
- Compose/config-tree delivery reads the required secret as UID 10001 without logging it.
  Adapted smoke retains every §11.2 runtime/persistence/CRUD/outage check, adds signed A/B ownership,
  replay/key/denial/metadata/management checks, and cleans up only its own resources on success
  and forced failure. Existing Inventory nginx stays a local fixture; no Keycloak/new Compose
  service fixture or credentials in argv is introduced.
- Existing architecture/domain/controller/migration/observability/Kafka/OpenAPI regressions pass
  with intended credentials and narrow Security layer rules. Full mvn -B -ntp clean verify reports
  BUILD SUCCESS and smoke prints Container smoke test passed; removing its isolated containers
  and test volume. These local gates do not claim real-provider combined acceptance.

## T2 — Document policies and wire local Swagger PKCE

**Commit:** docs: document reservation security and configure local PKCE

**Depends on:** T1.

**Refs.** `requirements.md` AC2.1, AC3.2, AC4.4, AC5.4, AC7.1, AC7.2, AC7.4, AC8.1, AC8.2;
`design.md` §3, §4.1, §5.2, §6, §8, §9, §10, §11.2, §11.4.

**DoD.**

- Running HTTP/OpenAPI assertions prove default generators disabled, approved anonymous GET docs
  only in local, invalid supplied bearer rejected, and no management docs. PKCE uses the exact
  reservation-swagger callback, issuer-derived endpoints, S256, openid and no secret/persistence.
- All six operations retain IDs/named schemas/business statuses with the owner-field changes of
  design §4.2, and declare bearer/OAuth2 alternatives
  with empty permission-scope lists, exact independent own/any/manage descriptions, common safe
  401/403/challenges and concealed ownership 404. Creation documents authenticated external key
  scope/replay/expiry and safe dependency errors. No operation-ID dispatcher is added.
- README and OpenAPI show POST orderId/items only, five-field PUT, read-only response customerId,
  issuer/subject ownership, no mapping provisioning and staff self-owned creation. They document the
  V6 reservation/outbox/replay reset, immutable owners, staff PUT/DELETE without Inventory release,
  opaque orders, scoped totals, replay-after-delete denial and uncertain-claim reconciliation.
- README documents explicit issuer/host setup, private config-tree/Compose/IDE delivery and UID
  readability, rotation/recipient refresh/restart, revocation window, exact error distinctions,
  listener policy and bounded metrics. Examples expose no usable credentials in output/history/
  argv and explain standalone fixture evidence versus external Common acceptance.
- Full mvn -B -ntp clean verify reports BUILD SUCCESS and the final configuration's isolated
  smoke emits its existing success message with normal and forced-failure cleanup assertions.

## T3 — Record implementation acceptance and coordinated release

**Commit:** docs: record reservation security acceptance and Common handoff

**Depends on:** T1 and T2.

**Refs.** `requirements.md` AC8.1, AC8.2, AC8.3, AC8.4; `design.md` §5.2, §11.1, §11.2,
§11.3, §11.4.

**DoD.**

- A dated implementation-acceptance.md links every AC to actual regression-sensitive assertions,
  unskipped suite counts, full Maven BUILD SUCCESS and the exact local smoke success. It separately
  links specification evaluation; no planned policy is presented as runtime evidence.
- Cutover instructions stop/drain old writers, coordinate final secured binary and consolidated V6
  migration, discard development reservation/outbox/replay data once in V6, provision
  human grants and dedicated secret before customer access, and prohibit mixed-version writers.
  Rollback and uncertain-operation reconciliation match the design without automatic resubmission.
- C1's exact policy/grant/identity/mount/real-provider checklist is linked and marked pending unless
  actual external evidence exists. Phase 4 is complete only with Combined container smoke verification
  passed; broader Phase 5 operations and Phase 7 production hardening remain named pending gates.
- Final full mvn -B -ntp clean verify reports BUILD SUCCESS and final service smoke prints its
  exact success message, with dates/scope recorded. Run spec-self-eval after any authorized spec
  edits; planning evidence does not complete implementation tasks.

## C1 — Provision Common policy and restore complete authenticated workflows

**Commit (Common repository):** feat: provision reservation permissions and verify secured workflows

**Depends on:** T1, T2 and a separate Common-scoped implementation request. No current sibling edits.

**Refs.** `requirements.md` AC2.1, AC2.3, AC2.5, AC3.1, AC3.3, AC4.1, AC4.2, AC5.1, AC5.2,
AC5.3, AC6.1, AC7.1, AC8.3, AC8.4; `design.md` §3, §4.1, §5.1, §6, §7, §10, §11.3, §11.4.

**DoD.**

- Desired realm policy/independent expectations/shared contract contain exactly the eight roles,
  explicit CUSTOMER own and STAFF create/any-read-list-cancel/manage composites and constrained reservation-access mappings
  for both approved human clients. Other Swagger/machine clients gain no inbound Reservation
  privileges. Update/read-back/no-op reapply preserves subjects, keys, credentials and existing
  Inventory/Pricing grants; scope requests expose held roles rather than granting roles.
- reservation-service retains exactly inventory-api plus inventory:reserve, no human roles or
  arbitrary Inventory powers. Only its recipient credential is mounted into Reservation; canonical
  URLs, exact callback/origin and UID 10001 readability are verified without secret output.
- Isolated real Keycloak PKCE through both clients and actual three service images proves both
  direct subject ownership/own access/scoped totals, cross-customer get/cancel/replay denial including same
  order/key values, staff self-owned create and any/manage powers and anonymous/machine/overgrant denials.
  No mapping SQL/bootstrap or runtime customer claim mapper appears.
- Observe actual outbound signed reservation-service identity, distinct same-external-key Inventory
  keys across subjects, token reuse/30-second renewal and all-RESERVED restriction. Wrong audience,
  insufficient permission, bad credential and token outage yield safe outcomes without unauthorized
  local mutation; 401 eviction and 403 retention are distinguished. Tokens remain in runner memory.
- Real cancellation/outbox/expiry release and pricing/inventory/reservation workflows pass without
  widening machine grants or bypassing enforcement. Common's complete combined smoke prints
  Combined container smoke verification passed and cleanup leaves developer resources unchanged.
  SECURITY_SPEC.md and the service acceptance link record actual Phase 4 evidence while broader
  Phase 5 operational and Phase 7 production gates stay pending.

## Acceptance-criteria traceability

| ACs | Design sections | Regression-sensitive task DoD |
| --- | --- | --- |
| AC1.1, AC1.2 | §2, §8 | T1 signed-token/header validation and zero-interaction assertions |
| AC1.3 | §3, §8 | T1 independent permission/caller/route matrix and denial-before-parsing |
| AC1.4 | §2 | T1 cold-start/JWKS cached/unknown/rotation/outage assertions |
| AC2.1 | §4.1, §4.2 | T1 token-derived storage/issuer query tests; T2 API docs; C1 real identities |
| AC2.2, AC2.4 | §4.2, §5.1 | T1 new-user access and rejected owner input with zero lock/data/downstream assertions |
| AC2.3, AC2.5, AC2.6 | §3, §4.2 | T1 customer operation/page/count/404/header/order/filter tests; C1 real denial flows |
| AC3.1, AC3.3 | §3 | T1 independent staff/self-create/overgrant matrix; C1 real grants |
| AC3.2, AC3.4 | §3, §4.2 | T1 replacement/deletion/deadline/event and locked cancellation race assertions |
| AC4.1 | §5.1 | T1 fixed hash/UUID vectors, cross-user keys, renewal/restart/client tests; C1 actual downstream keys |
| AC4.2, AC4.3 | §5.1, §7, §8 | T1 reduced/lost/target-mismatch/deleted replay, no partial snapshots, auth/dependency rollback/headers |
| AC4.4 | §4.1, §5.2 | T1 upgrade/reset/preservation tests; T2 documented cutover |
| AC4.5 | §5.2 | T1 equal-key cleanup/monitoring/chunk regression tests |
| AC5.1, AC5.2 | §5.1, §6 | T1 actual client, stable principal, key and renewal tests; C1 real machine tokens |
| AC5.3 | §6, §7 | T1 complete failure table, eviction/retention, same-key retry/circuit/rollback; C1 provider negatives |
| AC5.4 | §6 | T1 configuration/destination/redirect/timeout/secret assertions; T2 file-delivery docs |
| AC6.1 | §9, §11.1 | T1 no-context worker/Kafka/outbox regression and token-absence assertions; C1 release flows |
| AC6.2, AC6.3, AC6.5 | §9 | T1 real listener/probe/spoof/statelessness assertions |
| AC6.4 | §9 | T1 exact metadata URL/fields/header/outage/wrong-listener assertions |
| AC7.1, AC7.2 | §10 | T2 default/local docs/PKCE/port/bearer assertions; C1 real browser login |
| AC7.3 | §4.2, §7, §8 | T1 exact HTTP/proxy errors and marker capture assertions |
| AC7.4 | §8, §10 | T1 bounded-counter/telemetry tests; T2 operation/README assertions |
| AC8.1, AC8.2 | §11.1, §11.2, §11.4 | T1–T2 lifecycle/isolated suites; T1/T2 smoke; T3 actual final acceptance |
| AC8.3 | §11.3 | C1 exact policy/identity/real-provider/combined assertions; T3 external evidence status |
| AC8.4 | §11.3, §11.4 | T3/C1 separated dated evidence and pending Phase 5/7 assertions |
