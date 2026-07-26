# Reservation Service Skeleton Tasks

## 1. Delivery

Each task is one safe, reviewable commit-sized increment. Dependencies are explicit below;
this task does not require automatically committing the user's working tree. All references
are to this feature's requirements.md and design.md. No shipped migration is edited.

## 2. Implementation tasks

### T1 — Build and persistence foundation

**Commit:** `build: bootstrap reservation persistence`

**Depends on:** None.

**Refs.** requirements.md AC1.1, AC1.2, AC5.1–AC5.4, AC7.1–AC7.3; design.md §1.1, §1.2, §2.1, §2.2, §2.3, §4.4.

**DoD.**

- Maven and Wrapper enforce the pinned Java/build baseline; package produces executable reservation.jar; clean verify runs tests and formatting without skips.
- PostgreSQL 18.4 Testcontainers tests prove reservation-role login, owned schema/table/history, no application objects in public, unchanged foreign sentinel, and denied foreign-schema mutation.
- V1 is recorded once; subsequent migration and application startup preserve a committed reservation. Missing schema and invalid Hibernate mapping configuration fail startup.
- Direct SQL invalid inserts fail for serial/identifier/date/status constraints; duplicate serial/customer pairs insert successfully with distinct generated reservation IDs.
- Model tests prove initial HELD and immutable microsecond creation time; all unit tests run without a context.

### T2 — CRUD, collection, and errors

**Commit:** `feat: expose reservation crud api`

**Depends on:** T1.

**Refs.** requirements.md AC1.1–AC1.6, AC2.1–AC2.4, AC3.1–AC3.5, AC7.1, AC7.2, AC7.5; design.md §1.2, §2.1, §2.2, §3.1, §3.2, §3.3, §3.4, §4.4.

**DoD.**

- Full-stack tests assert credential-free POST 201/Location/HELD, GET all eight persisted values, and repeated serial/customer acceptance with distinct IDs.
- PUT changes every mutable field including all status values, keeps ID/timestamp, and never upserts; rejected writes preserve original data. DELETE returns empty 204 and subsequent GET and DELETE return 404.
- Parameterized tests reject missing/unknown/server-managed properties, malformed JSON, unconvertible field types and values failing validation after default string conversion, invalid UUIDs/status/date ranges; same-day and historical periods succeed.
- Paging tests assert default envelope/totals, bounds, empty/out-of-range pages, all eight sorts in both directions and ID tie-breaking. Unknown/repeated/blank/overflow/unsupported collection inputs return 400.
- Errors assert problem content type/envelope, sorted violations, 404, 405/Allow, 406, 415 and fixed sanitized 500. Unit tests use Mockito to assert service interactions and error sanitation.
- ArchUnit enforces the specified packages, layer access, cycles, component naming and controller ownership of MVC mappings. clean verify passes without skips.

### T3 — Executable docs and operational configuration

**Commit:** `feat: add reservation openapi and health probes`

**Depends on:** T2.

**Refs.** requirements.md AC4.1, AC4.2, AC5.4, AC6.3–AC6.5, AC7.1; design.md §4.1, §4.2, §4.4.

**DoD.**

- Generated OpenAPI tests assert all five operation IDs and paths, request/response field sets, types/constraints/examples, success/error responses, Location and page parameters; Swagger UI resolves without credentials.
- Health integration tests assert UP probes with no details and non-exposure of other Actuator endpoints; datasource/Flyway/JPA startup gates remain active.
- Application YAML contains no credentials; documented environment overrides control connectivity. Outage/recovery behavior is verified by T4's live smoke test. clean verify passes.

### T4 — Containers and contributor documentation

**Commit:** `build: package and document reservation service`

**Depends on:** T3.

**Refs.** requirements.md AC5.2, AC6.1–AC6.5, AC7.1–AC7.5; design.md §2.3, §4.2, §4.3, §4.4.

**DoD.**

- Multi-stage image builds and the isolated Compose smoke test proves PostgreSQL 18.4 prerequisite bootstrap, correct role/schema, UID/GID 10001 and a runtime with no compiler or Maven.
- Smoke assertions prove persisted data survives application restart and stack recreation with retained volume; database outage returns readiness 503 and liveness 200, followed by readiness recovery.
- Smoke cleanup removes only its unique test project and test volume, with bounded polling; both shell scripts pass syntax checks.
- README contains runnable setup/shutdown and CRUD curl examples, exact port/configuration/docs/health paths, a shared-database connection workflow, migration ownership and append-only rules, platform prerequisites and build/smoke commands. Compose credentials are labeled development-only.
- Wrapper reports pinned Maven; `mvn -B -ntp clean verify` ends with BUILD SUCCESS with all checks active. Spec self-evaluation records traceability evidence and any remaining weaknesses.

### T5 — Share the replacement and response DTO

**Commit:** `refactor: reuse reservation dto for replacement`

**Depends on:** T4.

**Refs.** requirements.md AC1.1, AC1.3, AC1.4, AC3.1, AC4.2, AC7.1; design.md §2.1, §2.2, §3.3, §4.1, §4.4.

**DoD.**

- PUT and its converter use `ReservationDTO`; the separate replacement request class is removed, while POST retains `CreateReservationRequest` and still creates HELD reservations.
- All six mutable fields retain PUT validation. Missing, malformed, blank, oversized, unknown, invalid-status and invalid-period values return 400 without changing persisted data.
- Supplying id or timestamp on PUT, including explicit null, returns 400; successful PUT preserves both, and POST/GET/PUT responses retain all eight fields.
- OpenAPI PUT and response references share `ReservationDTO`; id/timestamp are readOnly, writable fields are required, and the documented PUT example omits managed fields and succeeds against the running API.
- `mvn -B -ntp clean verify` returns BUILD SUCCESS with all existing checks and the added contract regression tests active; spec self-evaluation records current traceability.

### T6 — Remove custom string coercion configuration

**Commit:** `refactor: use default jackson string conversion`

**Depends on:** T5.

**Refs.** requirements.md AC1.1, AC1.4, AC3.1, AC7.1; design.md §2.2, §3.1, §3.3, §4.4.

**DoD.**

- Remove `JacksonConfig` without adding a replacement customizer; leave unrelated JSON settings and DTO validation unchanged.
- POST and PUT tests prove integer, fractional and boolean identifier inputs are persisted and returned as strings, with HELD creation and unchanged ID/timestamp on replacement.
- Tests retain rejection and non-mutation assertions for unconvertible objects/arrays, invalid statuses/dates, missing/blank/oversized values, unknown fields and managed fields.
- `mvn -B -ntp clean verify` returns BUILD SUCCESS without skipped checks; spec self-evaluation records current traceability.

## 3. Traceability

| Criteria | Design | Task regression evidence |
| --- | --- | --- |
| AC1.1–AC1.3 | §2.1, §2.3, §3.1 | T1 persistence and T2 create/get/duplicates |
| AC1.4–AC1.6 | §3.3 | T2 mutable fields, immutability, missing/delete behavior |
| AC2.1–AC2.4 | §3.2 | T2 page shape, sorts, bounds and invalid parameters |
| AC3.1–AC3.2 | §2.2, §3.3 | T2 validation and accepted period boundaries |
| AC3.3–AC3.5 | §3.4 | T2 exact problems/protocol and sanitation |
| AC4.1–AC4.2 | §4.1 | T3 live docs and generated-contract assertions |
| AC5.1–AC5.4 | §2.3, §4.2 | T1 migration/isolation/restart/startup failure |
| AC6.1–AC6.2 | §4.3 | T4 image, bootstrap and stack persistence |
| AC6.3 | §4.2, §4.3 | T3 environment configuration and T4 Compose |
| AC6.4–AC6.5 | §4.2 | T3 healthy probes and T4 outage/recovery |
| AC7.1–AC7.2 | §1.2, §4.4 | T1–T3 lifecycle, Mockito and real PostgreSQL suites |
| AC7.3 | §4.4 | T1 Wrapper and T4 documented commands |
| AC7.4 | §4.3 | T4 isolated smoke assertions and cleanup |
| AC7.5 | §1.2, §3.1 | T2 credential-free operations |
