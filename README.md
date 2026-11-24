# RentFlow Reservation Service

Reservation CRUD with atomic, idempotent batch creation, asynchronous cancellation, executable
Swagger documentation, Flyway migrations, PostgreSQL persistence, Inventory integration, health
probes, container packaging, and automated verification. Creation assigns `HELD`, rejects a
second active reservation for an item, and atomically claims every item as `RESERVED` through
Inventory. Every hold has a durable ten-minute deadline and is cancelled automatically if it is
still `HELD` when that deadline passes. Manual and timed cancellation use the same transactional
outbox to release the item through Kafka without making an HTTP request wait for Inventory.

The implementation follows the sibling Pricing service's MVC layers, DTO/converter pattern,
Problem Details errors, page envelope, build checks, and schema ownership conventions.
The sources of truth are [the service-skeleton spec](.specs/service-skeleton/requirements.md),
[the reservation-creation spec](.specs/reservation-creation/requirements.md),
[the reservation-cancellation spec](.specs/reservation-cancellation/requirements.md),
and [the security spec](.specs/security/requirements.md).

## Platform

Java 25, Spring Boot 4.1.0, Maven 3.9, PostgreSQL 18.4, Kafka 4.3.1, Flyway, Spring Data
JPA/Hibernate, Spring Kafka, Springdoc 3.0.3, and Testcontainers 2.0.5. Hibernate, JDBC, Jackson,
Kafka clients, and Flyway versions are managed by Spring Boot. Maven Wrapper pins Maven 3.9.16 and
verifies its distribution checksum.

Local prerequisites: JDK 25 and Docker with Compose v2. Maven 3.9 is needed for the bare `mvn`
command; `./mvnw` works without a system Maven installation. The wrapper downloads its pinned
distribution and may require `curl`/`wget` and `unzip`. The container smoke script also requires
Bash, `curl`, `jq`, and `setfacl` (the `acl` package).

## Run the local stack

Configure the issuer and the protected recipient credential as described in **Security setup**
below before running Compose. Common must provision the Reservation human grants before real
customer/staff login can authorize calls; its separate C1 handoff remains pending.

```bash
docker compose up --build --detach --wait
```

Swagger UI: <http://localhost:8082/swagger-ui.html>
OpenAPI: <http://localhost:8082/v3/api-docs>
API: <http://localhost:8082/api/v1/reservations>

The standalone development stack runs PostgreSQL on `127.0.0.1:5433`, the application on
`127.0.0.1:8082`, and an internal deterministic Inventory stub for local creation requests. It
does not own a Kafka broker; cancellation remains durably buffered in the outbox until a broker is
available. Use the `rent-flow-common` stack for the complete local Reservation-to-Inventory flow,
or supply `KAFKA_BOOTSTRAP_SERVERS` and activate the `local` profile when connecting this service
to a development broker.
PostgreSQL stores data in the named `rentflow-postgres-data` volume mounted at
`/var/lib/postgresql` for PostgreSQL 18. Use `INVENTORY_BASE_URL` when running against Inventory.

```bash
docker compose stop
docker compose start
# Remove containers and the network, retaining committed data:
docker compose down
```

`docker compose down --volumes` additionally deletes this stack's database data. Use it only when
you deliberately want a fresh local database. Bootstrap scripts run only when a database volume
is initialized; changing environment variables does not rename an existing role or rotate its password.

Compose's `rentflow-admin-local` and `reservation-local` passwords are disposable development
fixtures, not deployment credentials. The application uses `reservation` by default, never the database
administrator. Override local settings through environment variables or a gitignored `.env`:

| Variable | Local Compose default | Purpose |
| --- | --- | --- |
| POSTGRES_PORT | 5433 | Host PostgreSQL port; loopback only |
| RESERVATION_PORT | 8082 | Host application port; loopback only |
| POSTGRES_PASSWORD | rentflow-admin-local | Local bootstrap administrator password |
| RESERVATION_DB_USER | reservation | Local application role and owner of the reservation schema |
| RESERVATION_DB_PASSWORD | reservation-local | Local reservation role password |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 | Kafka bootstrap address for cancellation publication |
| RESERVATION_CANCELLATION_TOPIC | rentflow.reservation.cancelled.v1 | Version-1 source topic |
| RESERVATION_CANCELLATION_EXPIRATION_HOLD_DURATION | 10m | Lifetime assigned to newly created holds |

## Run Java locally

Start only the local database, then launch the application using its role:

```bash
docker compose up --detach --wait rentflow-postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/rentflow \
SPRING_DATASOURCE_USERNAME=reservation \
SPRING_DATASOURCE_PASSWORD=reservation-local \
SERVER_PORT=8082 \
./mvnw -B -ntp spring-boot:run
```

Stop Java with Ctrl+C; `docker compose down` stops the local database while retaining its volume.
The application itself defaults to port 8080 when `SERVER_PORT` is absent. A packaged build runs
with `java -jar target/reservation.jar` using the same environment variables.

## Connect to RentFlow's existing shared database

RentFlow services share **one database named `rentflow`**, with separate roles and owned schemas.
The Compose stack above is a standalone development convenience. To use an existing RentFlow
database (including Pricing's local database), run Reservation against that instance after the
platform/database administrator provisions the `reservation` login role and role-owned
`reservation` schema. Do not start another PostgreSQL container for that workflow.

For a fresh role/schema, an administrator can run the following in `psql` connected to `rentflow`;
the password command prompts without putting a password into SQL history:

```sql
CREATE ROLE reservation LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password reservation
CREATE SCHEMA reservation AUTHORIZATION reservation;
```

These are platform prerequisites, not application migrations. The role needs database CONNECT
and exclusive ownership of its own schema and objects; it must not have permission to create
objects in `public` or write another service's schema. Provisioning must account for pre-existing
roles/schemas and any grants on an existing shared database.

Set the application connection without an administrator account, for example:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/rentflow
export SPRING_DATASOURCE_USERNAME=reservation
read -rs -p 'Reservation database password: ' SPRING_DATASOURCE_PASSWORD
export SPRING_DATASOURCE_PASSWORD
export SERVER_PORT=8082
./mvnw -B -ntp spring-boot:run
```

Application configuration contains no passwords or fallback datasource credentials. It fails
startup if the datasource, required schema, Flyway migration, or Hibernate validation fails.

## Reservation contract

The API requires an approved human bearer token and accepts JSON. It returns a generated UUID `id`,
client-assigned `serialNumber`/`orderId`, token-derived read-only `customerId`, inclusive `startDate`/`endDate`, immutable
UTC creation `timestamp` and `holdExpiresAt` deadline (microsecond precision), and `status`.

Order IDs are case-sensitive, nonblank strings up to 64 characters. Customer IDs are opaque verified token subjects; ownership also compares the persisted issuer. Creation delegates
serial-number validity and existence to Inventory. Dates use `YYYY-MM-DD`, years 0001–9999, with
`endDate >= startDate`; same-day periods are accepted and new intents must start on or after the
current PostgreSQL UTC date. An item has an active local reservation when a `HELD` or `CONFIRMED`
row ends today or later. Outdated and `CANCELLED` rows do not block creation.

| Operation | Path | Success |
| --- | --- | --- |
| POST | /api/v1/reservations | 201 with an ordered HELD reservation array |
| GET | /api/v1/reservations/{id} | 200 reservation |
| GET | /api/v1/reservations | 200 bounded page |
| PUT | /api/v1/reservations/{id} | 200 full replacement |
| POST | /api/v1/reservations/{id}/cancel | 204 local cancellation committed or already cancelled |
| DELETE | /api/v1/reservations/{id} | 204 permanent deletion |

PUT requires the four client-assigned detail fields plus `status` (`HELD`, `CONFIRMED`, or
`CANCELLED`). It preserves the original owner, ID, timestamp, and `holdExpiresAt` and never inserts a missing
reservation. There are no status-transition restrictions yet. POST requires a canonical UUID-v4 `Idempotency-Key` and an
envelope containing common `orderId` plus 1–100 unique item serials and periods. It
returns no `Location` header. Unknown properties and supplied owner fields are rejected. Missing item GET/PUT/DELETE returns
404. PATCH is unsupported.

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and
machine-readable `code`; validation errors add `violations` sorted by field and message.
Invalid requests return 400, forbidden methods 401/403, unacceptable response media 406, unsupported
request media 415, and unexpected failures a sanitized 500. Swagger describes schemas and examples.

### Cancellation and Inventory release

`POST /api/v1/reservations/{id}/cancel` has no request body and requires no `Idempotency-Key`.
`HELD` and `CONFIRMED` become `CANCELLED`; an already `CANCELLED` reservation is an idempotent
`204` no-op. A missing reservation returns `404 RESERVATION_NOT_FOUND`, and a malformed UUID
returns `400 VALIDATION_FAILED`. Existing PUT and DELETE behavior remains unchanged and can still
bypass event publication in this increment.

Successful creation samples PostgreSQL time once for the whole batch. Each new reservation stores
that value as `timestamp` and stores `holdExpiresAt` using the configured hold duration (ten
minutes by default). A database-backed worker polls every five seconds and cancels the oldest due
reservation only if it is still `HELD`. `CONFIRMED` and `CANCELLED` rows are left unchanged.
Deadlines survive restarts and configuration changes; a new duration applies only to reservations
created afterward. Each expiration commits independently, with at most 100 transitions and five
seconds of work per run.

Timed expiration uses the same row locking, five-field version-1 event, and atomic outbox write as
manual cancellation. PostgreSQL advisory locking coordinates multiple service instances. A race
between the endpoint and the worker therefore creates one transition and one event. Legacy PUT
remains unrestricted: restoring an expired reservation to `HELD` preserves its original deadline
and makes it eligible for a later expiration run.

The status transition and one `reservation_cancellation_outbox` row commit in the same local
transaction. The response does not call Inventory, wait for Kafka, or mean that Inventory has
already released the item. A scheduled relay polls every second and publishes the globally oldest
eligible row to `rentflow.reservation.cancelled.v1`. One PostgreSQL transaction advisory lock
allows only one active relay instance, and a failed oldest row deliberately blocks later rows to
preserve FIFO order.

The Kafka key is the exact case-sensitive serial number in UTF-8. The compact UTF-8 value contains
exactly these version-1 fields:

```json
{"eventId":"d880f919-2b5c-4f7e-a56d-e047e7d932a6","eventType":"ReservationCancelled","eventVersion":1,"occurredAt":"2026-09-15T15:30:00Z","serialNumber":"DRILL-001"}
```

The producer uses `acks=all`, idempotence, and byte-array serializers. Every retry reuses the
persisted event ID, key, and JSON bytes. PostgreSQL and Kafka do not share a transaction, so
delivery is at least once: a lost acknowledgement or a database failure after broker acceptance
can publish a duplicate. Inventory's inbox suppresses repeated effects by event ID; producer
idempotence alone is not an end-to-end exactly-once guarantee.

Failed sends retry indefinitely with persisted exponential backoff from one second to a nominal
five-minute cap and 20-percent jitter. Kafka is intentionally excluded from readiness so a broker
outage does not prevent database-backed cancellation; nonzero pending count and oldest-event age
are the operational signals. The source topic has three partitions, delete cleanup, and seven-day
retention. Local/test replication is one; production infrastructure targets replication three
and minimum in-sync replicas two and provisions the topic before rollout.

Acknowledged rows are retained for 30 days and deleted in skip-locked chunks of 1000 by the 03:30
UTC cleanup. Unpublished rows are never aged out. If a source record expires before Inventory
processes it, operators must first inspect Inventory state and inbox history, account for version
1's stale-first-delivery limitation, and only then republish the retained exact key/value under
the same event ID. There is no automatic replay or administrative replay endpoint.

Operational metrics are `reservation.cancellation.outbox.publish.attempts`,
`reservation.cancellation.outbox.retries.scheduled`, `reservation.cancellation.outbox.pending`,
`reservation.cancellation.outbox.oldest.age`, and the
`reservation.cancellation.outbox.cleanup.*` family. Expiration adds
`reservation.cancellation.expiration.transitions`,
`reservation.cancellation.expiration.failures`,
`reservation.cancellation.expiration.overdue`, and
`reservation.cancellation.expiration.oldest.age`. Metric labels exclude business identifiers.
Structured diagnostics retain useful exception details and validated equipment/event identifiers;
payload dumps and deliberately captured customer/order data are excluded. See Observability below.

## Try every operation

These examples require a staff token from the approved PKCE client and `jq` to capture the first
ID. Set `RENTFLOW_CURL_CONFIG` to a protected curl config containing the Authorization header;
use the private-input procedure below. No token is placed in curl arguments. Use future dates
appropriate to the current PostgreSQL UTC date.

```bash
BASE_URL=http://localhost:8082
IDEMPOTENCY_KEY=$(cat /proc/sys/kernel/random/uuid)
CREATED=$(curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body --silent --show-error \
  --request POST --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data '{"orderId":"ORDER-001","items":[{"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"}]}' \
  "$BASE_URL/api/v1/reservations")
RESERVATION_ID=$(printf '%s' "$CREATED" | jq -er '.[0].id')
printf '%s\n' "$CREATED"

# The same key and body replays the identical terminal result.
curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body --request POST --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data '{"orderId":"ORDER-001","items":[{"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"}]}' \
  "$BASE_URL/api/v1/reservations"

curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body \
  "$BASE_URL/api/v1/reservations?page=0&size=20&sort=startDate&direction=desc"

curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body --request PUT --header 'Content-Type: application/json' \
  --data '{"serialNumber":"DRILL-001","orderId":"ORDER-001","startDate":"2026-10-02","endDate":"2026-10-05","status":"CONFIRMED"}' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body --request POST --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID/cancel"

curl --config "$RENTFLOW_CURL_CONFIG" --fail-with-body --request DELETE --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"
```

Listing returns `{"content":[...],"page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}`.
Defaults are page 0, size 20, sort `id`, direction `asc`. Size is 1–100. Any response field may
be the primary sort; ties use `id ASC`. Direction is case-insensitive. Unknown, repeated, blank,
invalid query parameters and offsets above 2147483647 are rejected. Filtering is deferred.

Creation first authorizes the caller and reads its verified owner, then acquires an issuer/subject-scoped
PostgreSQL transaction advisory lock for the external key, checks the compact
terminal ledger, validates the current PostgreSQL UTC date, checks local active reservations once,
and derives a stable, caller-distinct UUID key for `PATCH /api/v1/inventory/status`. The local transaction remains open
through that synchronous call. On success, the created reservations and replayable `201` response
snapshot commit together. Known validation, local-conflict, and Inventory business failures are
also stored and replayed.

Configure Inventory with `INVENTORY_BASE_URL` (default `http://inventory`); connect/read timeouts
are 500/1500 ms. Resource failures and Inventory 502/503/504 responses receive up to five total
foreground attempts inside one circuit-breaker call. Retry waits use exponential nominal delays of
200, 400, 800, and 1600 ms with 20-percent jitter.

Reservation does no creation work after the HTTP response returns. Matching terminal responses
replay from `reservation_creation_requests` for seven days after current permission/target checks.
Successful replay by every caller, including staff, verifies current issuer/subject ownership of all referenced IDs;
deleted rows return 404, and a cancelled but still-owned row replays the original snapshot.
Replay makes no Inventory call. Staff follows the same creation replay rules as customers.
Concurrent execution of the same key returns `409 IDEMPOTENCY_IN_PROGRESS` with `Retry-After: 1`;
different payloads under an unexpired key return `422 IDEMPOTENCY_KEY_REUSED`. Temporary `502` and
`503` integration results are not stored, so a same-key client retry starts another synchronous
attempt and uses Inventory's own idempotency record if Inventory previously committed. The only
background task deletes expired ledger rows.

## Migrations and health

V1 creates `reservation.reservations`. The replacement V2 creates the six-column terminal
idempotency ledger, adds the database timestamp default, and adds the active lookup index. V3 adds
the cancellation outbox and its partial FIFO/cleanup indexes. V4 adds hold deadlines; V5 adds
optional immutable W3C origin context to the outbox. The single security V6 scopes creation keys to callers and resets development data for direct
immutable ownership, without an intermediate mapping table (details below).
Flyway history lives in
`reservation.flyway_schema_history`.
Hibernate validates mappings and never generates DDL. Flyway cannot create schemas and is the
sole mechanism for evolving Reservation-owned application objects after platform provisioning.
Add migrations at `src/main/resources/db/migration/V{n}__description.sql` or
`src/main/java/com/rentflow/db/migration/V{n}__description.java`. Never edit a shipped migration,
create the shared database/role/schema in a migration, or modify another service's objects.

`/livez` reports application liveness independently of the database. `/readyz` includes database
health, but deliberately excludes Kafka, and returns 503 during a database outage before
recovering when PostgreSQL returns. Health and Prometheus are available only on the separate
management listener; detailed health components and other Actuator endpoints are not exposed.
The runtime image's healthcheck uses `/readyz` on `${SERVER_PORT:-8080}`.

## Observability

Prometheus metrics, ECS JSON stdout logs and Micrometer/OpenTelemetry traces share the identity
`service=rent-flow-reservation` and `environment=local` by default. The service runs without an
observability backend. Trace export is disabled until deployment opts in; JVM, pool, HTTP,
Resilience4j and business meters remain available independently of trace sampling/export.

| Environment variable | Default and purpose |
| --- | --- |
| `MANAGEMENT_SERVER_PORT` | `8087`; health/Prometheus listener |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1`; Compose overrides to `0.0.0.0` without publishing it |
| `OBSERVABILITY_ENVIRONMENT` | `local`; metric tag, ECS environment and OTel resource |
| `OBSERVABILITY_INSTANCE` | `HOSTNAME`, falling back to `standalone`; ECS node and OTel instance |
| `TRACING_EXPORT_ENABLED` | `false`; enable OTLP trace export explicitly |
| `TRACING_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces`; full HTTP/protobuf endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `1.0`; local full sampling, configurable for deployment |
| `RESERVATION_KAFKA_CLUSTER_ID` | Empty; optional actual broker cluster ID to bypass discovery |

Host execution exposes `http://127.0.0.1:8087/actuator/prometheus` and private health. Application
port 8080 (published as 8082 locally) serves `/livez` and `/readyz` but denies every
`/actuator/*` endpoint with 401 (anonymous) or 403 (authenticated). Collector, Kafka and Inventory health do not affect readiness. Production
network protection, telemetry access, authentication/TLS and retention belong to deployment.

OTLP export is asynchronous, with a 2048-span queue, batches up to 512 and two-second connect/call
timeouts. Queue overflow or collector failure can lose traces without changing business results.
Logs go to stdout for Alloy; there is no separate OTLP log or metric export. HTTP/JDBC spans retain
SQL statement shape and actual paths, with route templates used for metric labels. Automatic
bodies, headers, query strings, baggage and bound SQL parameters are not captured. Application
diagnostics exclude credentials and customer/order fields; validated equipment, reservation and
event identifiers are permitted as searchable fields. Ordinary exception messages, causes and
stacks remain useful internal diagnostics; these controls do not redact arbitrary SQL literals
or values already contained in driver exceptions. ECS stack limits default to 16384 characters
and throwable depth 64 through Spring's configurable settings.

### Metric meanings

| Meter | Interpretation |
| --- | --- |
| `reservation.creation.idempotency{outcome}` | Existing `attempt/completion/replay/busy/mismatch` code-path counters; completion does not prove commit |
| `reservation.creation.committed{outcome}` | Newly stored terminal ledger outcomes after commit; excludes replay, transient busy, local mismatch and rollback |
| `reservation.creation` | Method execution timer/histogram with bounded outcome and replayed tags; method return precedes transaction commit |
| `reservation.creation.batch.size` | Submitted item count once per service invocation, including replay/failure; no per-item label |
| `reservation.inventory.claim` | One complete logical claim including circuit evaluation, all physical attempts and retry waits |
| `http.client.requests` | Physical HTTP attempts; compare with the logical claim rather than summing their counts |
| `resilience4j.*` | Existing retry/breaker meters with unchanged policies and meanings |
| `reservation.cancellation.outbox.publish.attempts{outcome}` | Existing scheduler results: published after committed proxy return; failed includes send and relay/database errors |
| `reservation.cancellation.outbox.retries.scheduled` | Existing retry scheduling counter |
| `reservation.cancellation.outbox.publish.duration{outcome}` | Physical send/ack wait: acknowledged, failed, timeout or interrupted; acknowledged alone does not prove DB commit |
| `reservation.cancellation.outbox.publication.delay` | Occurrence-to-database publication timestamp, sampled after successful publication commit; does not prove Inventory release |
| `reservation.cancellation.outbox.pending` / `.oldest.age` | Unpublished row count and oldest occurrence age at measurement |
| `reservation.cancellation.expiration.overdue` / `.oldest.age` | HELD rows past their deadline and oldest delay beyond that deadline |
| `reservation.cancellation.expiration.transitions` / `.failures` | Existing committed expiration transitions and failed worker calls |
| `reservation.creation.cleanup.*` / `reservation.cancellation.outbox.cleanup.*` | Existing deleted/failures/duration signals and eligible-row backlog under existing retention |
| `reservation.observability.backlog.last.success.timestamp{backlog}` | PostgreSQL measurement epoch-seconds for outbox, expiration, creation_cleanup or outbox_cleanup; zero before first success |
| `reservation.observability.backlog.refresh.failures{backlog}` | Measurement failures, separate from worker failures |
| `reservation.worker.run{worker,outcome}` | Actual worker invocation timer, including caught failures; empty/backoff/lock-busy normal runs are successful |
| `reservation.worker.last.run.timestamp` / `.last.success.timestamp` | Application-clock epoch-seconds; zero before first invocation/success |

Committed creation outcomes are `created`, `validation_failed`, `active_conflict`,
`inventory_missing`, `inventory_unavailable`, `invalid_reference` and `key_reused`. They are
process-local telemetry: a process crash between commit and its callback can lose a sample.
Neither these counters nor result logs are a durable exactly-once audit ledger.

Operational backlog refresh defaults to 15 seconds; both cleanup backlogs refresh every
60 seconds. Set `reservation.observability.operational-backlog-fixed-delay`,
`cleanup-backlog-fixed-delay` and `query-timeout` (default five seconds) to positive durations.
Monitoring runs independently of business workers, including disabled workers, and never queries
on a scrape. Initial values are NaN, successful empty measurements are zero, and refresh failure
retains the entire last successful snapshot. An old age measurement must be shown as stale rather
than healthy. Four worker threads and four dedicated monitoring threads prevent a blocked relay
from monopolizing scheduling capacity. Counts/ages describe the shared database: dashboards must
use the maximum of fresh replicas, not sum these gauges. Process-local counter rates can be summed.

### Investigations and Common handoff

For a slow creation, open the HTTP trace, inspect JDBC connection/query spans and the logical
Inventory claim, then compare its duration with physical HTTP attempts and retry-wait events.
Committed outcome counters tell whether terminal work committed; execution counters/timers alone
cannot establish that. Retry WARNs and circuit state changes explain dependency degradation.

For a stalled cancellation, first check backlog freshness and oldest pending
age, eventId and publication failure diagnostics. The committed cancellation INFO includes the
origin trace. Each later send attempt starts its own trace linked to that durable origin and its
relay worker, including after a restart. KafkaTemplate supplies the outgoing trace headers;
Inventory continues that publication trace. Navigate links or search `originTraceId` to return
to the cancellation request. Legacy outbox rows with no valid context still publish normally.
HTTP 204 means local cancellation committed; publication acknowledgement and later Inventory
release are separate stages. Confirm release through Inventory's
`inventory.reservation.cancellation.completion.delay`, processing outcomes, lag and DLT signals.
No diagnostic call to Inventory is added by Reservation.

Common's separate integration change must scrape `reservation:8087/actuator/prometheus`
(`job=reservation`, 15s interval, 5s timeout), enable trace export to
`http://rentflow-alloy:4318/v1/traces`, and collect ECS container logs through
`rentflow.observability.collect=true` with bounded service/environment stream labels. Prometheus
supplies instance/job; trace/event/origin IDs and runtime instance remain searchable log fields.
Grafana needs origin-link navigation, HTTP/JVM/pool and creation/claim panels, publication/hold
backlogs, worker progress and cleanup/freshness views. The optional Kafka cluster ID must be the
actual target broker ID. With pinned Spring Kafka 4.1.0, failed normal cluster-ID discovery can
block the relay for the admin timeout (default 30s) on repeated attempts; it does not run in the
cancellation transaction. Later-version discovery retry throttling must not be assumed.

Initial proposed warnings for Common are:

- Fresh oldest pending publication age above 120s for 1m; oldest overdue hold age above 30s for 30s.
- Operational measurements absent after 45s uptime or older than 45s; cleanup measurements absent
  after 180s uptime or older than 180s. No fresh replica means unknown service backlog.
- Sustained publication/worker errors over 5m.

Age warnings remain active when workers are disabled. Interpret worker run/success timestamps
alongside deployment configuration and scheduling cadence during diagnosis.
Monitoring freshness is independent of worker flags. These provisional operational
thresholds are not SLOs; notification routing and tuning follow measured baselines in Common.

## Verification

The mandatory lifecycle is:

```bash
mvn -B -ntp clean verify
```

Or use the pinned wrapper: `./mvnw -B -ntp clean verify`. The lifecycle compiles and packages the
application, enforces Java/Maven/dependency rules, runs no-context unit tests with Mockito,
ArchUnit layer checks, PostgreSQL 18.4 and Kafka 4.3.1 Testcontainers integration tests,
migration/API/generated-OpenAPI assertions, and Palantir formatting checks. Docker must be
available; no tests or checks are skipped. Reports are in `target/surefire-reports` and
`target/failsafe-reports`.

If Spotless reports differences:

```bash
./mvnw -B -ntp spotless:apply
mvn -B -ntp clean verify
```

Verify the production image and live operational behavior:

```bash
./scripts/container-smoke-test.sh
```

The script uses a unique Compose project, disposable development credentials, and dynamic host
ports. It checks prerequisite-only bootstrap, restricted role/schema ownership, a non-root JRE
runtime, creation/retrieval and persistence across application/stack restart, and independent
liveness versus database-aware readiness through an outage and recovery. It removes only its
isolated containers and test volume on exit. The Docker build runs `package` (including unit tests);
the separate Maven `verify` command is required for integration and formatting verification.


## Security setup

The API validates signed access tokens for `reservation-api`, using Common's exact issuer,
RS256, required access-token claims and 30-second skew. Permissions come only from allowlisted
`resource_access["reservation-api"].roles`. Approved human clients are `reservation-swagger`
and `rentflow-pkce-check`; machine clients have no inbound business access. The shared realm
roles expand to these independent permissions:

| Operation | CUSTOMER | STAFF |
| --- | --- | --- |
| POST collection | reservation:create, caller owns rows | reservation:create, caller owns rows |
| GET item | reservation:read, owned row | reservation:read:any |
| GET collection | reservation:list, owned rows and totals | reservation:list:any |
| POST item/cancel | reservation:cancel, owned locked row | reservation:cancel:any |
| PUT / DELETE item | Denied | reservation:manage |

Ownership needs no provisioning or mapping table. Manage grants only PUT/DELETE and does
not imply any other permission. Staff PUT preserves the owner and may change order/serial/dates or set any defined
status, including resurrecting a cancelled row with its original deadline. Neither PUT nor DELETE
emits a cancellation event or releases Inventory. Use the explicit cancel operation for release.
`orderId` is an unverified business reference; matching it never grants access. Unsupported
customer/order filters, forged user headers and token-foreign customer claims confer no ownership.

For local development, Common's issuer is
`http://auth.rentflow.test:8180/realms/rentflow-local`; add `127.0.0.1 auth.rentflow.test` on the
host and supply matching container DNS/network access. Development and smoke never trust each
other's issuer. Production requires its own HTTPS issuer and HTTPS Inventory/token transport.
Explicit local/test profiles permit isolated HTTP; neither disables authentication.

Set these nonsecret Compose inputs:

```bash
export RESERVATION_ISSUER_URI=http://auth.rentflow.test:8180/realms/rentflow-local
export RESERVATION_JWKS_URI="$RESERVATION_ISSUER_URI/protocol/openid-connect/certs"
export RESERVATION_TOKEN_URI="$RESERVATION_ISSUER_URI/protocol/openid-connect/token"
export RESERVATION_CLIENT_SECRET_FILE=/absolute/protected/recipient/client-secret
```

The recipient file must contain **only reservation-service's** client secret and be readable by
container UID 10001. Use a private recipient directory/copy and UID 10001 ownership plus mode 0400
(or an equivalent narrowly granted ACL); keep Common's original source host-owned 0600 and do
not broaden its permissions. Compose mounts only that recipient file read-only under
`/run/secrets/spring.security.oauth2.client.registration.inventory.client-secret`. There is no
fallback secret. The optional config-tree import does not make the required property optional.
For IDE execution, use a user-owned 0700 `.secrets/ide/` directory with the same property filename
and mode 0600, then activate `local`. This directory is ignored by Git and Docker. Do not put
credentials into YAML, environment dumps, process arguments, shell history or image layers.
After an explicit Common rotation, refresh the recipient and restart Reservation; hot reload is
not implemented. Existing issued tokens remain valid through their remaining lifetime plus skew.

Only creation needs the Inventory OAuth client. It acquires Client Credentials as
`reservation-service`, with only `inventory-api` / `inventory:reserve`, caches the authorized
client under that fixed application principal, and renews 30 seconds before expiry. No incoming
human token or X-User header is forwarded. Background gateway calls need no human context;
expiration, outbox relay, cleanup and monitoring retain their internal authorization boundary.
Tokens remain in process memory and never enter reservation rows, replay snapshots or Kafka.

JWT/JWKS and token configuration is explicit, so valid configuration can start with Keycloak
offline. Known cached signing keys/tokens continue within their supported lifetimes; unresolved
keys or required token acquisition fail closed. Keycloak, Inventory and Kafka are outside the
existing readiness contract. Token connect/read limits are two seconds and token/resource
redirects are disabled. Inventory 401 fails the current request and evicts the machine token;
only a later explicit request reacquires. Inventory 403 retains the token. Neither causes an
automatic retry of the write. Existing eligible resource retries reuse the exact downstream key.

| Failure | Public result |
| --- | --- |
| Missing/invalid inbound credential | 401 AUTHENTICATION_REQUIRED |
| Missing capability or unapproved client | 403 ACCESS_DENIED |
| Missing/foreign own get/cancel, or deleted success replay IDs (including staff) | 404 RESERVATION_NOT_FOUND |
| Rejected machine credentials/token shape or Inventory 401/403 | 502 INVENTORY_SERVICE_ERROR |
| Token transport/rate-limit/provider outage or exhausted resource outage | 503 INVENTORY_SERVICE_UNAVAILABLE |

Inbound security responses are fixed Problem Details. Missing credentials challenge with Bearer,
invalid credentials with invalid_token and authenticated denials with insufficient_scope; no
provider descriptions or role scopes are advertised. Ownership 404 and dependency 502/503 have
no authentication challenge. Security/dependency failures have no replay headers or saved terminal
outcome. Local rollback cannot undo an already committed Inventory claim: retry explicitly with
the same external key within retention, rather than changing keys or assuming remote rollback.
There is no new distributed transaction or compensation flow.

### Customer identity

Creation assigns `customerId` from the verified access token's `sub` and stores its exact `iss`
alongside it. Ownership checks compare both; no Keycloak user lookup, registration or operator
mapping is required. Username, email, customerId claims and user headers confer no ownership.
`customerId` is response-only, and the issuer stays internal. POST accepts only `orderId` and
`items`; PUT accepts only serial number, order, dates and status. Supplying `customerId` or
`customerIssuer` in either body returns 400. A new permitted user can create immediately and
has an empty own collection until creation.

Staff creation uses `reservation:create` and makes the staff caller the owner, just like customer
creation. `reservation:create:any` grants nothing. Staff retains independently granted cross-customer
read/list/cancel and manage powers. PUT preserves the original owner, including when staff edits
another customer's reservation; creating on behalf of another customer and owner reassignment
are outside this API. Account/realm replacement requires a separately designed identity migration.

### Local Swagger and private API-tool credentials

Documentation is disabled by default. The explicit `local` profile enables application-only
Swagger/OpenAPI GET paths with Authorization Code, S256 PKCE, client `reservation-swagger`,
`openid`, and no secret or persisted authorization. The exact callback is
`http://localhost:8082/swagger-ui/oauth2-redirect.html`, origin `http://localhost:8082`; a different
port/origin needs an explicit Common registration update. API roles are not OAuth scopes.
For scripts, obtain a short-lived access token through an approved PKCE client, then enter it
without echoing it or saving its value in history:

```bash
umask 077
mkdir -p .secrets/tools
chmod 700 .secrets/tools
export RENTFLOW_CURL_CONFIG="$PWD/.secrets/tools/access.curl"
read -r -s -p 'Access token: ' rentflow_access_token
printf '\n'
printf 'header = "Authorization: Bearer %s"\n' "$rentflow_access_token" > "$RENTFLOW_CURL_CONFIG"
unset rentflow_access_token
curl --config "$RENTFLOW_CURL_CONFIG" --fail --silent --show-error http://localhost:8082/api/v1/reservations
# Remove the private credential when finished.
rm -- "$RENTFLOW_CURL_CONFIG"
```

### Migration, operational policy and acceptance

V1–V5 remain unchanged. The consolidated V6 discards development reservations, cancellation outbox
rows and creation replay records, adds required issuer ownership and caller-scoped creation keys,
and creates the `(customer_issuer, customer_id, id)` index. It creates no identity mapping table. Identity columns use text,
without the old domain-ID length restriction. This is an explicitly authorized development reset,
with no legacy compatibility path; repeating Flyway preserves all post-migration rows.

Stop/drain old writers and workers, back up any needed data, deploy the matching binary/schema,
inject credentials and provision human grants before customer access. Reconcile outstanding
Inventory claims before cutover; clearing Reservation data does not release Inventory. Old binaries
cannot safely write the new schema. Rollback requires coordinated schema/data restoration, and
an old key becomes a new operation after reset. Implementation tests use isolated databases;
they do not run this reset against the developer environment. Databases that applied the earlier
V6/V7 pair need a separate development database recreation; this change does not repair Flyway
history or reset those databases.

Current create authorization always precedes scoped replay. Different subjects using the same
external UUID have separate locks, results and derived Inventory UUIDs. Token renewal and approved
client changes preserve those identities. Different staff and customer subjects do not share outcomes. Cleanup joins on both key components and retains the
existing seven-day TTL, 1000-row chunks, runtime budgets and unexpired neighboring scopes.

Only minimal GET /livez and /readyz are public application probes. The separate private management
listener permits GET health, health/liveness, health/readiness and prometheus; all other management
paths are 403 without challenges, regardless of credentials. Listener selection uses the actual
local port, never forwarded headers. Native protected-resource metadata GETs are public before
bearer processing and expose only Spring's request-derived resource URL, issuer, header bearer
method and certificate-binding=false. There is no public-origin rewriting or blanket well-known
allowlist. APIs are stateless without cookies, login redirects, Basic/form authentication or CORS.

Bounded diagnostics are `reservation.security.denials{reason=authentication|authorization,
surface=application|management}` and `reservation.security.outbound.failures{dependency=inventory,
reason=credentials|token_response|token_unavailable|upstream_unauthenticated|upstream_forbidden}`.
No principal IDs, scope hashes, token/secret/provider text or request bodies are security metric
labels or diagnostics. Existing approved cancellation trace correlation remains; diagnostics
follow existing retention/access and are not a durable business audit.

The isolated container smoke uses the existing Inventory nginx fixture plus a temporary host-side
signing/token runner, its own PostgreSQL volume, private credential files and token-derived test owners.
It retains runtime/persistence/CRUD/outage checks and adds authenticated ownership/replay denials;
it proves no real Keycloak/Inventory interoperability. `RESERVATION_SMOKE_FAIL_AFTER_START=1`
exercises cleanup after an intentional failure. See the [implementation acceptance](.specs/security/implementation-acceptance.md)
for actual service evidence. Common's separate C1 must provision all eight human roles (STAFF now uses reservation:create), preserve
both narrow machine grants, verify actual PKCE/service flows and restore
`Combined container smoke verification passed`. Broader integrated operational acceptance and
production hardening remain Phases 5 and 7; HTTP authentication does not secure Kafka publishers.
