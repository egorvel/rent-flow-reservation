# Reservation security implementation acceptance

Date: 2026-11-24.
Scope: approved identity/API revision of Reservation T1–T3, implemented and locally verified. External Common C1 remains pending.

The six follow-up decisions replace the earlier mapping-based contract. Development reservation,
cancellation outbox and replay data may be discarded in the append-only migration. Implementation
verification uses disposable databases; no running developer database, realm or sibling repository
was changed. The earlier 2026-11-19 test counts describe the prior implementation and are superseded
by the revision evidence below.

## Implemented behavior

POST accepts orderId/items and assigns customerId from the verified token subject. Each reservation
stores its exact issuer and subject directly, with no mapping table, registration or provider lookup.
Own read/list/cancel queries and successful replay counts compare both identity components in SQL.
Response customerId is read-only and the issuer is absent. POST/PUT owner inputs are rejected with 400.

Staff and customers share reservation:create and create for themselves; create:any grants nothing.
The eight remaining permissions are independent. Staff retains any-read/list/cancel and manage.
PUT accepts only serial number, order, dates and status and preserves the original owner, creation
time and hold deadline. DELETE remains permanent and neither administrative operation emits release.
Permission and own/any decisions belong to ReservationAuthorizationPolicy in the service package;
CustomerIdentityService only reads verified authentication and has no permission or database logic.

The consolidated V6 upgrades V5 directly: it deletes development reservation/outbox/replay rows,
creates no mapping table, requires nonblank customer_issuer, adds caller-scoped composite ledger
keys/expiry indexing, widens identity columns to text and installs the issuer/subject index.
V1–V5 are unchanged. The user explicitly authorized consolidating the earlier V6/V7 pair. Flyway reapplication preserves post-migration data; no legacy compatibility
branch or inferred owner is implemented. Stop/drain writers/workers and reconcile outstanding
Inventory claims before a separately managed cutover: deleting local data does not release Inventory.

Caller-scoped keys/locks and deterministic downstream UUIDs are unchanged. The payload fingerprint
is version 2 without customer input. Every caller needs current create permission and live owned
rows for successful replay; deleted rows return 404 even to staff, without replay headers. Cancelled
owned rows still replay their original snapshot. OAuth, probe, metadata, documentation, telemetry,
resilience and no-human-context worker contracts remain covered by the complete regression suite.

## Earlier identity/API verification

These dated results predate migration consolidation; the current consolidation gate follows below.

- `mvn -B -ntp clean verify` on JDK 25.0.2: **BUILD SUCCESS**, finished at
  2026-11-24 18:16:09 Europe/Warsaw. **91 unit + 244 integration = 335 tests**, zero
  failures/errors/skips; Spotless clean. No test or formatting skip flags were used.
- `scripts/container-smoke-test.sh`: exit 0, emitted exactly
  **`Container smoke test passed; removing its isolated containers and test volume`**.
  Creation/replay uses token-derived owners without mapping SQL; same-key users remain isolated,
  and customer A can still read its row after staff replacement. Existing image, management,
  outage, restart, retained-volume and CRUD checks passed.
- `RESERVATION_SMOKE_FAIL_AFTER_START=1 scripts/container-smoke-test.sh`: reached
  **`ERROR: Intentional smoke failure for cleanup verification`**, exited 1 as expected and
  completed teardown. Read-back after both runs found **zero** smoke containers, volumes,
  networks, private fixture directories and signing-runner processes.
- `git diff --check` and `bash -n scripts/container-smoke-test.sh` pass. V1–V5 are unchanged.

The first verification run identified an obsolete customerId request in the exporter-outage
fixture and an invalid hold deadline in the new migration fixture. Both fixtures were corrected
without weakening their assertions or changing timeouts. The complete lifecycle above was then
rerun successfully. Java sources remained unchanged after that final lifecycle; later edits only
clarified the specs and recorded evidence. All migrations ran only in disposable test/smoke databases.

## Migration consolidation verification

- `mvn -B -ntp clean verify` on JDK 25.0.2: **BUILD SUCCESS**, finished
  2026-11-24 21:37:11 Europe/Warsaw; **91 unit + 244 integration = 335 tests**, zero
  failures/errors/skips, with Spotless clean and no skipped checks.
- SecurityMigrationIT upgrades seeded V5 directly through exactly one V6, clears the three
  authorized development tables, verifies absence of the mapping, required issuer/text/index
  constraints, long subjects and preservation of new reservation/outbox/replay rows on reapply.
  Flyway history contains exactly versions 1–6. MigrationIT's restart/schema/role checks also pass.
- V1–V5 are unchanged; V7 is removed. No running database or Flyway history was reset/repaired.
- Final consolidated-migration `scripts/container-smoke-test.sh`: exit 0, printed
  **`Container smoke test passed; removing its isolated containers and test volume`**.
  Startup, creation/replay/ownership, Inventory/DB outages, application restart, retained-volume
  stack recreation and staff CRUD passed with the six-version history.
- Final `RESERVATION_SMOKE_FAIL_AFTER_START=1 scripts/container-smoke-test.sh`: reached the
  intentional cleanup-verification failure and exited 1 as expected. Teardown succeeded;
  read-back after both runs found zero smoke containers/volumes/networks, private directories
  and signing-runner processes. Both runs used disposable databases and private fixture files.
- `git diff --check` passes. The rewrite changes migration history, not the final schema or
  runtime authorization behavior. Developer databases with superseded history require separate
  recreation; no Flyway repair/validation bypass or developer environment reset was performed.

Specification evaluation: **9 PASS / 0 WEAK / 0 FAIL**; see the
[revision report](eval-reports/eval-report-2026-11-24-2135.md). It uses the skill fallback checklist
because .specs/_eval-checklist.md is absent, and does not substitute for runtime evidence.

## Acceptance-criteria evidence

The complete AC-to-test mapping below describes the revised assertions. External real-provider
claims remain conditional on C1; fixture JWT/Inventory tests do not establish combined acceptance.

| ACs | Evidence |
| --- | --- |
| AC1.1, AC1.2 | [SecurityIT](../../src/test/java/com/rentflow/SecurityIT.java) signed required/invalid claims, algorithms/types, duplicate/query credentials and no-business-interaction assertions; [JWT validator tests](../../src/test/java/com/rentflow/config/ReservationJwtValidatorTest.java) |
| AC1.3 | SecurityIT independent eight-permission/caller/method matrix, malformed denied bodies and unmatched routes |
| AC1.4 | [Security config tests](../../src/test/java/com/rentflow/config/ReservationSecurityConfigTest.java) cached/unknown/overlapping keys and exact skew; [SecurityStartupIT](../../src/test/java/com/rentflow/SecurityStartupIT.java) actual startup/probes with unreachable providers |
| AC2.1 | [SecurityPersistenceIT](../../src/test/java/com/rentflow/SecurityPersistenceIT.java) exact issuer/subject get/page/count/locked-query constraints, including equal subjects under different issuers; SecurityIT token-derived ownership and long subjects; no mapping provisioning |
| AC2.2, AC2.4 | SecurityIT first-use access without provisioning and 400 owner-input rejection before business/Inventory work |
| AC2.3, AC2.5, AC2.6 | SecurityIT customer get/list/totals/cancel, foreign/missing 404, same opaque order and unsupported filter denial; [ReservationIT](../../src/test/java/com/rentflow/ReservationIT.java) existing page/binding regressions |
| AC3.1, AC3.3 | SecurityIT own/any/manage independence, staff self-owned creation and overgranted-machine denial |
| AC3.2, AC3.4 | ReservationIT existing replacement/deletion/status/deadline/outbox assertions; SecurityIT immutable foreign owner during staff PUT and real locked cancellation versus staff deletion |
| AC4.1 | [AuthenticatedIdempotencyTest](../../src/test/java/com/rentflow/service/AuthenticatedIdempotencyTest.java) exact independent hash/UUID vectors and namespace separation; SecurityIT actual distinct downstream gateway keys and approved-client replay |
| AC4.2, AC4.3 | SecurityIT changed permission/target/current row ownership and staff replay denial after deletion; [InventoryOAuthIT](../../src/test/java/com/rentflow/InventoryOAuthIT.java) no local rows/ledger/headers on auth failure; ReservationIT existing fingerprint/expiry/rollback regressions |
| AC4.4 | [SecurityMigrationIT](../../src/test/java/com/rentflow/SecurityMigrationIT.java) seeded V5→consolidated V6 reservation/outbox/replay reset, absent mapping, identity/index constraints, long identity text and no repeated reset |
| AC4.5 | SecurityPersistenceIT same UUID across expired/unexpired scopes, independent backlog and selective cleanup; existing cleanup/monitoring tests |
| AC5.1, AC5.2 | InventoryOAuthIT actual HTTP proxy/manager, fixed machine principal, all-RESERVED body, no user header relay, reuse/renewal and no-request gateway calls |
| AC5.3 | InventoryOAuthIT complete token error/timeout/malformed/redirect matrix, 401-only eviction, 403 retention, unchanged circuit counts, no throwable security diagnostics and five identical-key resource attempts |
| AC5.4 | [Client configuration tests](../../src/test/java/com/rentflow/config/InventoryClientConfigurationValidatorTest.java), [Boot registration tests](../../src/test/java/com/rentflow/InventoryClientConfigurationIT.java), OAuth transport/redirect assertions and smoke UID/file injection |
| AC6.1 | Existing cancellation/expiry/outbox/relay/Kafka/monitoring tests retain no-context worker, transactional, ordering, recovery and trace assertions; actual Kafka release with real services pending C1 |
| AC6.2, AC6.3, AC6.5 | SecurityIT real listener/probe/forwarded-header/private-metrics/stateless-denial assertions; SecurityStartupIT and existing observability tests |
| AC6.4 | SecurityIT native metadata root/nested/encoded/trailing paths, exact four fields, query removal and malformed bearer independence; decoder outage fixture and cold-start metadata |
| AC7.1, AC7.2 | [OpenApiIT](../../src/test/java/com/rentflow/OpenApiIT.java) exact six operations, PKCE scheme/initializer, local invalid bearer rejection; SecurityIT default docs denial; real browser PKCE pending C1 |
| AC7.3 | SecurityIT exact safe responses/headers and synthetic marker logs; InventoryOAuthIT provider-error response/log/exported-span leakage checks |
| AC7.4 | OpenApiIT operation-specific permissions/schemes/statuses, bounded security metrics implementation and README matrix/identity/cutover/file-delivery documentation |
| AC8.1 | Full unskipped Maven lifecycle; actual [MethodSecurityIT](../../src/test/java/com/rentflow/MethodSecurityIT.java) non-web proxy; signed HTTP, real PostgreSQL and actual client tests above |
| AC8.2 | [Container smoke](../../scripts/container-smoke-test.sh) signed A/B ownership/replay, admin denial, scoped key reuse, private management, runtime/CRUD/restart/volume/Inventory/DB outage and cleanup |
| AC8.3 | **Pending Common C1**: desired policy, direct real token subjects, actual PKCE/service flows and complete combined smoke |
| AC8.4 | This record distinguishes the gates; C1 and broader Phase 5 operational / Phase 7 production evidence remain pending |


## External Common handoff — pending

A separate Common-scoped request must provision exactly the eight roles/composites in design §3,
using reservation:create for STAFF as well as CUSTOMER. Remove the prior create:any proposal.
Use real verified fixture token subjects as customer IDs; remove mapping SQL/provisioning from the
future combined runner. Preserve existing Inventory/Pricing human policy and both narrow machine
grants, inject only Reservation's recipient secret, verify UID readability and canonical URLs.

Real PKCE through reservation-swagger and rentflow-pkce-check, actual three-service creation/replay,
owner isolation, staff self-owned creation/administration, token reuse/renewal, failure handling and
cancellation/outbox/expiry flows must pass the complete Common gate and print
`Combined container smoke verification passed`. C1, broader Phase 5 operational acceptance and
Phase 7 production hardening remain pending. HTTP security does not authenticate Kafka publishers.
