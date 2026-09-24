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
and [the reservation-cancellation spec](.specs/reservation-cancellation/requirements.md).

## Platform

Java 25, Spring Boot 4.1.0, Maven 3.9, PostgreSQL 18.4, Kafka 4.3.1, Flyway, Spring Data
JPA/Hibernate, Spring Kafka, Springdoc 3.0.3, and Testcontainers 2.0.5. Hibernate, JDBC, Jackson,
Kafka clients, and Flyway versions are managed by Spring Boot. Maven Wrapper pins Maven 3.9.16 and
verifies its distribution checksum.

Local prerequisites: JDK 25 and Docker with Compose v2. Maven 3.9 is needed for the bare `mvn`
command; `./mvnw` works without a system Maven installation. The wrapper downloads its pinned
distribution and may require `curl`/`wget` and `unzip`. The container smoke script also requires
Bash, `curl`, and `jq`.

## Run the local stack

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

The API is unauthenticated in this increment and accepts JSON. It returns a generated UUID `id`,
client-assigned `serialNumber`, `customerId`, `orderId`, inclusive `startDate`/`endDate`, immutable
UTC creation `timestamp` and `holdExpiresAt` deadline (microsecond precision), and `status`.

Customer/order IDs are case-sensitive, nonblank strings up to 64 characters. Creation delegates
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

PUT requires all five client-assigned detail fields plus `status` (`HELD`, `CONFIRMED`, or
`CANCELLED`). It preserves ID, timestamp, and `holdExpiresAt` and never inserts a missing
reservation. There are no status-transition restrictions yet. POST requires a canonical UUID-v4 `Idempotency-Key` and an
envelope containing common `customerId`/`orderId` plus 1–100 unique item serials and periods. It
returns no `Location` header. Unknown properties are rejected. Missing item GET/PUT/DELETE returns
404. PATCH is unsupported.

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and
machine-readable `code`; validation errors add `violations` sorted by field and message.
Invalid requests return 400, unsupported methods 405, unacceptable response media 406, unsupported
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
`reservation.cancellation.expiration.oldest.age`. Labels and logs exclude serial numbers,
payloads, exception messages, and customer/order data.

## Try every operation

These examples use the local stack and `jq` to capture the first generated ID:

```bash
BASE_URL=http://localhost:8082
IDEMPOTENCY_KEY=$(cat /proc/sys/kernel/random/uuid)
CREATED=$(curl --fail-with-body --silent --show-error \
  --request POST --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data '{"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[{"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"}]}' \
  "$BASE_URL/api/v1/reservations")
RESERVATION_ID=$(printf '%s' "$CREATED" | jq -er '.[0].id')
printf '%s\n' "$CREATED"

# The same key and body replays the identical terminal result.
curl --fail-with-body --request POST --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data '{"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[{"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"}]}' \
  "$BASE_URL/api/v1/reservations"

curl --fail-with-body "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --fail-with-body \
  "$BASE_URL/api/v1/reservations?page=0&size=20&sort=startDate&direction=desc"

curl --fail-with-body --request PUT --header 'Content-Type: application/json' \
  --data '{"serialNumber":"DRILL-001","customerId":"CUSTOMER-001","orderId":"ORDER-001","startDate":"2026-10-02","endDate":"2026-10-05","status":"CONFIRMED"}' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --fail-with-body --request POST --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID/cancel"

curl --fail-with-body --request DELETE --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"
```

Listing returns `{"content":[...],"page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}`.
Defaults are page 0, size 20, sort `id`, direction `asc`. Size is 1–100. Any response field may
be the primary sort; ties use `id ASC`. Direction is case-insensitive. Unknown, repeated, blank,
invalid query parameters and offsets above 2147483647 are rejected. Filtering is deferred.

Creation acquires a PostgreSQL transaction advisory lock for the public key, checks the compact
terminal ledger, validates the current PostgreSQL UTC date, checks local active reservations once,
and forwards the same key to `PATCH /api/v1/inventory/status`. The local transaction remains open
through that synchronous call. On success, the created reservations and replayable `201` response
snapshot commit together. Known validation, local-conflict, and Inventory business failures are
also stored and replayed.

Configure Inventory with `INVENTORY_BASE_URL` (default `http://inventory`); connect/read timeouts
are 500/1500 ms. Resource failures and Inventory 502/503/504 responses receive up to five total
foreground attempts inside one circuit-breaker call. Retry waits use exponential nominal delays of
200, 400, 800, and 1600 ms with 20-percent jitter.

Reservation does no creation work after the HTTP response returns. Matching terminal responses
replay from `reservation_creation_requests` for seven days without local or Inventory work.
Concurrent execution of the same key returns `409 IDEMPOTENCY_IN_PROGRESS` with `Retry-After: 1`;
different payloads under an unexpired key return `422 IDEMPOTENCY_KEY_REUSED`. Temporary `502` and
`503` integration results are not stored, so a same-key client retry starts another synchronous
attempt and uses Inventory's own idempotency record if Inventory previously committed. The only
background task deletes expired ledger rows.

## Migrations and health

V1 creates `reservation.reservations`. The replacement V2 creates the six-column terminal
idempotency ledger, adds the database timestamp default, and adds the active lookup index. V3 adds
the cancellation outbox and its partial FIFO/cleanup indexes. Flyway history lives in
`reservation.flyway_schema_history`.
Hibernate validates mappings and never generates DDL. Flyway cannot create schemas and is the
sole mechanism for evolving Reservation-owned application objects after platform provisioning.
Add migrations at `src/main/resources/db/migration/V{n}__description.sql` or
`src/main/java/com/rentflow/db/migration/V{n}__description.java`. Never edit a shipped migration,
create the shared database/role/schema in a migration, or modify another service's objects.

`/livez` reports application liveness independently of the database. `/readyz` includes database
health, but deliberately excludes Kafka, and returns 503 during a database outage before
recovering when PostgreSQL returns. `/actuator/health` is also available; other Actuator endpoints
and detailed health components are not exposed.
The runtime image's healthcheck uses `/readyz` on `${SERVER_PORT:-8080}`.

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
