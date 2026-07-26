# Reservation Service Skeleton Design

## 1. Platform and architecture

### 1.1 Baseline

Follow Pricing's single-module Spring MVC service: Java 25, Spring Boot 4.1.0 (managed Hibernate,
Flyway, PostgreSQL JDBC and Jackson 3), Maven Wrapper 3.3.4/Maven 3.9.16, PostgreSQL 18.4,
Springdoc 3.0.3, Testcontainers 2.0.5, ArchUnit 1.4.2, Enforcer 3.6.2, Spotless 3.8.0 and
Palantir Java Format 2.96.0. Coordinates are `com.rentflow:rent-flow-reservation:0.0.1-SNAPSHOT`;
Boot entry point is `ReservationApplication`; executable artifact is `target/reservation.jar`.
The Pricing baseline has no need here for its Inventory integration or resilience dependencies.

### 1.2 Layers

Controllers depend on services, converters and DTOs. Converters map DTOs and models. Services
depend on repositories and models. Repositories depend on models. DTOs and models have no
application-layer dependencies. Config may depend on DTOs and services. Utils are optional and
independent. Use the packages prescribed by AGENTS.md and the existing Pricing ArchUnit style.
All unit tests run without Spring; use Mockito only for collaborators. No Security, H2, Lombok,
MapStruct, or new framework is needed. The API and docs are unauthenticated (AC7.5).

## 2. Resource, storage, and validation

### 2.1 Fields

`reservation.reservations` contains the following NOT NULL columns:

| JSON | Java | PostgreSQL | Rule |
| --- | --- | --- | --- |
| id | UUID | uuid primary key | JPA UUID generation; immutable; response only |
| serialNumber | String | varchar(64) | Pattern `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| customerId | String | varchar(64) | Nonblank, client assigned, case sensitive; no lookup |
| orderId | String | varchar(64) | Nonblank, client assigned, case sensitive; may aggregate many reservations |
| startDate | LocalDate | date | ISO YYYY-MM-DD, years 0001–9999 |
| endDate | LocalDate | date | ISO YYYY-MM-DD, years 0001–9999, >= startDate |
| timestamp | Instant | timestamptz(6) | Server UTC creation time, truncated to microseconds, immutable; response only |
| status | ReservationStatus | varchar(16) | HELD, CONFIRMED, CANCELLED |

Use `CreateReservationRequest` for POST and share `ReservationDTO` between PUT and responses.
Creation remains separate because its status is server-assigned and its workflow will evolve
independently. `ReservationDTO` validates all six mutable fields on PUT and includes all eight
fields in responses. Its id and timestamp use Jackson read-only access and OpenAPI readOnly
metadata. Enable FAIL_ON_IGNORED_PROPERTIES so supplying either managed field, including null,
still returns 400 rather than silently ignoring it. No validation groups or additional DTOs
are needed. The replacement contains the five manually assigned details and status. Creation
contains only those five details; sending status, id or timestamp is a 400 unknown-property failure.
The model assigns HELD and creation time during persistence. No public ID/timestamp mutator.
UUIDs avoid a distributed sequence dependency; strings defer cross-service identifier semantics.

### 2.2 Validation

Bean validation enforces mandatory values, serial syntax, and 64-character limits. Controller
validation checks the period and years before service invocation, reports `endDate` for a
reversed period, and accepts same-day and historical dates (AC3.1–AC3.2). No trimming or case
normalization. OpenAPI declares the canonical string representation. Objects/arrays cannot
populate String fields; null/blank/length and serial-pattern constraints remain active.
Unknown JSON properties and numeric status values fail; converting status 1 to "1" does
not make it HELD, CONFIRMED, or CANCELLED. Dates are strict ISO calendar dates; timestamp output
is UTC. Malformed/non-UUID item IDs return 400. PUT is full replacement and requires status,
including when keeping HELD.

### 2.3 Migration and isolation

V1 creates only `reservation.reservations` and its primary key and CHECK constraints for serial
syntax, nonblank identifiers, date bounds/order and status. There is no unique constraint on
serial_number or customer_id and no exclusion constraint: duplicate and overlapping rows are
deliberately accepted (AC1.2). No cross-schema foreign keys or secondary indexes are justified
until query patterns require them. `timestamp` maps to `created_at`; persisted creation times
use microsecond precision so POST and later GET agree.

Flyway uses `default-schema: reservation`, `schemas: reservation`, `create-schemas: false`.
Hibernate uses explicit schema mappings, default_schema reservation, `ddl-auto: validate`, and
`open-in-view: false`; Spring SQL initialization is disabled. All service writes use transactions;
reads are read-only transactions. PUT loads the existing entity and changes only mutable fields;
DELETE loads then removes it. No upsert. Last completed write wins; optimistic locking is deferred.
Database, restricted role, and owned schema are provisioned before application startup. The
bootstrap creates no application/history tables. Shipped migrations are append-only.
Tests use an administrator only for prerequisites and a foreign sentinel; Spring/Flyway run as
reservation. Verify ownership, inability to write another schema, unchanged sentinel, V1
recorded once, constraints, restart persistence, and startup failure with missing schema or
invalid mappings. These cover AC5.1–AC5.4.

## 3. HTTP contract

### 3.1 Resource paths and creation

All operations use `/api/v1/reservations`, JSON, and no credentials. POST accepts a valid
CreateReservationRequest, persists once without external lookups, and returns 201 with
ReservationDTO and relative `Location: /api/v1/reservations/{id}` (AC1.1–AC1.3).
Creation status is always HELD. GET `/{id}` returns the persisted DTO or 404.

### 3.2 Collection

GET collection accepts exactly one value each for `page` (default 0, >=0), `size` (default 20,
1–100), `sort` (default id, allowlisted eight JSON field names), `direction` (default asc,
case-insensitive asc/desc). Unknown, repeated, blank, malformed or out-of-range values return
400 violations. Reject page offsets greater than Integer.MAX_VALUE, matching JPA's offset
limit. Use typed `ReservationSortField`, PageRequest and Spring Data non-HATEOAS PagedModel
after mapping entities. Sort ties use id ASC unless id is primary. Empty/out-of-range pages
retain accurate totals; no filtering (AC2.1–AC2.4).

### 3.3 Mutation

PUT `/{id}` updates all five details plus status and returns 200 DTO. ID and creation timestamp
remain unchanged. Status may be any enum value regardless of previous status; workflows are
deferred. Invalid replacement returns 400 without mutation. Missing ID returns 404 and never
inserts. DELETE physically removes an existing entity, returns bodyless 204, and returns 404
for missing IDs. PATCH is unsupported (AC1.4–AC1.6).

### 3.4 Errors

Use Pricing's ResponseEntityExceptionHandler pattern and `ProblemResponse`/`ViolationResponse`
records. Problems contain type URI `urn:rentflow:problem:<slug>`, title, HTTP status, safe detail,
request-path instance, uppercase code, and validation-only violations sorted by field/message.
Catalogue: VALIDATION_FAILED/400, MALFORMED_JSON/400, RESERVATION_NOT_FOUND/404,
RESOURCE_NOT_FOUND/404, METHOD_NOT_ALLOWED/405 (Allow preserved), NOT_ACCEPTABLE/406,
UNSUPPORTED_MEDIA_TYPE/415, HTTP_ERROR for other framework client errors, INTERNAL_ERROR/500.
Do not echo malformed request text or internal exception messages. Unexpected-error logging
records method, path and exception class without potentially sensitive exception messages or
request bodies. HTTP failures retain relevant framework headers (AC3.3–AC3.5).

## 4. Documentation and operation

### 4.1 OpenAPI

Springdoc exposes `/v3/api-docs`, `/swagger-ui.html` and `/swagger-ui/index.html`; include only
`/api/v1/**`. Stable operation IDs: createReservation, getReservation, listReservations,
replaceReservation, deleteReservation. Describe request/response field formats, required fields,
bounds, inclusive period validation, initial HELD status, mutable update status, deterministic
paging, success codes, Location and applicable 400/404/405/406/415/500 problems. Valid schema
examples cover create, replace, response and errors. Generated-contract integration tests assert
operation paths/IDs, request and response properties, errors, parameters and UI accessibility
(AC4.1–AC4.2).

PUT and reservation responses reference the same `ReservationDTO` schema. Its id and timestamp
are readOnly and required only in responses; the remaining six fields are required in both
contexts. The schema example shows the complete response, while PUT supplies an explicit
request example without managed fields. Tests verify both examples, readOnly flags, required
writable fields, and successful execution of the documented PUT example.

### 4.2 Health and configuration

Only Actuator health is exposed with hidden details. Liveness includes only livenessState;
readiness includes readinessState and db. Additional paths `/livez` and `/readyz` are enabled.
Healthy probes return 200/UP. Database loss makes readiness 503 while liveness stays 200;
recovery restores readiness. Startup is gated by datasource, Flyway, and Hibernate.
Configuration uses SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
SPRING_DATASOURCE_PASSWORD, and SERVER_PORT. Application configuration contains no credentials.
Use bounded datasource connection/validation timeouts to make outage health checks useful
(AC6.3–AC6.5).

### 4.3 Container and local database

Adapt Pricing's Temurin 25.0.3_9 JDK/JRE noble multi-stage Dockerfile and pinned Wrapper.
Build with Maven package (unit tests run; Failsafe runs during separate verify). Runtime UID/GID
10001, executable `/opt/reservation/reservation.jar`, curl readiness healthcheck respecting
SERVER_PORT, and no compiler/Maven/cache. Docker ignore excludes local/IDE/git/env/build files.
Compose provisions standalone `rentflow-postgres` at postgres:18.4-alpine and `reservation`.
Default published application port 8081 avoids Pricing's 8080; PostgreSQL 5433 avoids Pricing's
5432. Both are configurable and bind loopback locally. The PostgreSQL 18 volume mounts
`/var/lib/postgresql`. Bootstrap shell safely quotes SQL identifiers/literals and provisions
only reservation role and owned schema. Local credentials are explicit development fixtures.

This Compose database is an isolated development convenience; existing shared-database users
run the application with their platform-provisioned datasource and avoid creating a second
database. README gives both paths and explains init scripts run only with a fresh volume.
Smoke tests use a unique Compose project and dynamically assigned host ports; teardown deletes
only the test project's containers/volume. Verify bootstrap before application starts, runtime
identity and absence of build tooling, create/retrieve, application restart, DB outage/recovery,
and stack recreation preserving its test volume (AC6.1–AC6.2, AC7.4).

### 4.4 Build, tests and README

Surefire runs *Test, Failsafe runs *IT at integration-test/verify. Enforcer requires Java 25 and
Maven 3.9 and excludes Spring Security. Spotless checks Palantir formatting at verify. Run
`mvn -B -ntp clean verify`; on formatting failure run `mvn spotless:apply` and rerun clean verify.
No test/check skip flags. Testcontainers requires Docker and always uses PostgreSQL 18.4.
The Wrapper pins Maven 3.9.16 with distribution checksum. Tests cover CRUD, duplicate acceptance,
date/status/JSON validation, paging/sorting, error sanitation, schema isolation/migrations,
OpenAPI and the prescribed ArchUnit layer graph (AC7.1–AC7.2).

README documents the exact platform, build/Wrapper, all datasource variables, local and Compose
workflows, shared-database prerequisite ownership, append-only migration paths, curl examples for
all operations, pagination, Swagger, health and isolated smoke verification. It labels initial
business limitations and development-only credentials (AC7.3).
