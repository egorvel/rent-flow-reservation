# RentFlow Reservation Service

Basic reservation CRUD with a versioned REST API, executable Swagger documentation, Flyway
migrations, PostgreSQL persistence, health probes, container packaging, and automated verification.
The service owns time-based availability and temporary holds; this initial scaffold only records
reservations. Creation always assigns `HELD`. Availability, overlap, external existence checks,
hold expiry, and status-transition rules will be added separately.

The implementation follows the sibling Pricing service's MVC layers, DTO/converter pattern,
Problem Details errors, page envelope, build checks, and schema ownership conventions.
The source of truth is [the service-skeleton spec](.specs/service-skeleton/requirements.md).

## Platform

Java 25, Spring Boot 4.1.0, Maven 3.9, PostgreSQL 18.4, Flyway, Spring Data JPA/Hibernate,
Springdoc 3.0.3, and Testcontainers 2.0.5. Hibernate, JDBC, Jackson, and Flyway versions are
managed by Spring Boot. Maven Wrapper pins Maven 3.9.16 and verifies its distribution checksum.

Local prerequisites: JDK 25 and Docker with Compose v2. Maven 3.9 is needed for the bare `mvn`
command; `./mvnw` works without a system Maven installation. The wrapper downloads its pinned
distribution and may require `curl`/`wget` and `unzip`. The container smoke script also requires
Bash, `curl`, and `jq`.

## Run the local stack

```bash
docker compose up --build --detach --wait
```

Swagger UI: <http://localhost:8081/swagger-ui.html>
OpenAPI: <http://localhost:8081/v3/api-docs>
API: <http://localhost:8081/api/v1/reservations>

The standalone development stack runs PostgreSQL on `127.0.0.1:5433` and the application on
`127.0.0.1:8081`, leaving Pricing's default ports available. PostgreSQL stores data in the named
`rentflow-postgres-data` volume mounted at `/var/lib/postgresql` for PostgreSQL 18.

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
| RESERVATION_PORT | 8081 | Host application port; loopback only |
| POSTGRES_PASSWORD | rentflow-admin-local | Local bootstrap administrator password |
| RESERVATION_DB_USER | reservation | Local application role and owner of the reservation schema |
| RESERVATION_DB_PASSWORD | reservation-local | Local reservation role password |

## Run Java locally

Start only the local database, then launch the application using its role:

```bash
docker compose up --detach --wait rentflow-postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/rentflow \
SPRING_DATASOURCE_USERNAME=reservation \
SPRING_DATASOURCE_PASSWORD=reservation-local \
SERVER_PORT=8081 \
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
export SERVER_PORT=8081
./mvnw -B -ntp spring-boot:run
```

Application configuration contains no passwords or fallback datasource credentials. It fails
startup if the datasource, required schema, Flyway migration, or Hibernate validation fails.

## Reservation contract

The API is unauthenticated in this increment and accepts JSON. It returns a generated UUID `id`,
client-assigned `serialNumber`, `customerId`, `orderId`, inclusive `startDate`/`endDate`, immutable
UTC creation `timestamp` (microsecond precision), and `status`.

Serial numbers follow `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Customer/order IDs are case-sensitive,
nonblank strings up to 64 characters. Dates use `YYYY-MM-DD`, years 0001–9999, with
`endDate >= startDate`. Same-day and historical periods are accepted. Identifiers are not trimmed
or normalized. Repeated serial/customer IDs and overlapping periods are accepted without lookup
or uniqueness constraints. Multiple reservations may share an order ID.

| Operation | Path | Success |
| --- | --- | --- |
| POST | /api/v1/reservations | 201 with Location and HELD reservation |
| GET | /api/v1/reservations/{id} | 200 reservation |
| GET | /api/v1/reservations | 200 bounded page |
| PUT | /api/v1/reservations/{id} | 200 full replacement |
| DELETE | /api/v1/reservations/{id} | 204 permanent deletion |

PUT requires all five client-assigned detail fields plus `status` (`HELD`, `CONFIRMED`, or
`CANCELLED`). It preserves ID/timestamp and never inserts a missing reservation. There are no
status-transition restrictions yet. POST accepts neither status nor ID/timestamp. Unknown
properties are rejected. Missing item GET/PUT/DELETE returns 404. PATCH is unsupported.

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and
machine-readable `code`; validation errors add `violations` sorted by field and message.
Invalid requests return 400, unsupported methods 405, unacceptable response media 406, unsupported
request media 415, and unexpected failures a sanitized 500. Swagger describes schemas and examples.

## Try every operation

These examples use the local stack and `jq` to capture the generated ID:

```bash
BASE_URL=http://localhost:8081
CREATED=$(curl --fail-with-body --silent --show-error \
  --request POST --header 'Content-Type: application/json' \
  --data '{"serialNumber":"DRILL-001","customerId":"CUSTOMER-001","orderId":"ORDER-001","startDate":"2026-10-01","endDate":"2026-10-03"}' \
  "$BASE_URL/api/v1/reservations")
RESERVATION_ID=$(printf '%s' "$CREATED" | jq -er '.id')
printf '%s\n' "$CREATED"

curl --fail-with-body "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --fail-with-body \
  "$BASE_URL/api/v1/reservations?page=0&size=20&sort=startDate&direction=desc"

curl --fail-with-body --request PUT --header 'Content-Type: application/json' \
  --data '{"serialNumber":"DRILL-001","customerId":"CUSTOMER-001","orderId":"ORDER-001","startDate":"2026-10-02","endDate":"2026-10-05","status":"CONFIRMED"}' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"

curl --fail-with-body --request DELETE --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/api/v1/reservations/$RESERVATION_ID"
```

Listing returns `{"content":[...],"page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}`.
Defaults are page 0, size 20, sort `id`, direction `asc`. Size is 1–100. Any response field may
be the primary sort; ties use `id ASC`. Direction is case-insensitive. Unknown, repeated, blank,
invalid query parameters and offsets above 2147483647 are rejected. Filtering is deferred.

## Migrations and health

V1 creates `reservation.reservations`; Flyway history lives in `reservation.flyway_schema_history`.
Hibernate validates mappings and never generates DDL. Flyway cannot create schemas and is the
sole mechanism for evolving Reservation-owned application objects after platform provisioning.
Add migrations at `src/main/resources/db/migration/V{n}__description.sql` or
`src/main/java/com/rentflow/db/migration/V{n}__description.java`. Never edit a shipped migration,
create the shared database/role/schema in a migration, or modify another service's objects.

`/livez` reports application liveness independently of the database. `/readyz` includes database
health and returns 503 during an outage, recovering when PostgreSQL returns. `/actuator/health`
is also available; other Actuator endpoints and detailed health components are not exposed.
The runtime image's healthcheck uses `/readyz` on `${SERVER_PORT:-8080}`.

## Verification

The mandatory lifecycle is:

```bash
mvn -B -ntp clean verify
```

Or use the pinned wrapper: `./mvnw -B -ntp clean verify`. The lifecycle compiles and packages the
application, enforces Java/Maven/dependency rules, runs no-context unit tests with Mockito,
ArchUnit layer checks, PostgreSQL 18.4 Testcontainers migration and API integration tests,
generated-OpenAPI assertions, and Palantir formatting checks. Docker must be available; no tests
or checks are skipped. Reports are in `target/surefire-reports` and `target/failsafe-reports`.

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
