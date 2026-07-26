# Reservation Service Skeleton Requirements

## Context

RentFlow needs a reservation service with basic CRUD and an executable engineering baseline.
This increment records reservations; availability checks and the creation workflow will be
refined separately. The reference is the sibling Pricing service's service-skeleton feature.
The required platform is Java 25, Spring Boot 4, Maven, PostgreSQL 18.4, Flyway, Spring Data JPA,
Hibernate, and Testcontainers.

Services share database `rentflow`. Reservation connects as the `reservation` login role and
owns only schema `reservation`. The platform provisions database, role, and schema; local
Compose and test setup provision equivalent prerequisites. Application migrations own only
Reservation tables, constraints, indexes, and schema-local Flyway history.

## User stories

### US1 — Manage reservations

As an internal consumer, I want to create, retrieve, replace, and delete reservations.

- **AC1.1 (Event-driven):** When a valid creation request is submitted to `POST /api/v1/reservations`, the service shall persist a reservation with a generated UUID `id`, immutable UTC creation `timestamp`, and `HELD` status, and return `201` with its representation and canonical `Location`.
- **AC1.2 (Event-driven):** When creation requests repeat a serial number or customer ID, the service shall accept them independently without uniqueness or external existence checks.
- **AC1.3 (Event-driven):** When an existing reservation is requested by ID, the service shall return `200` with `id`, `serialNumber`, `customerId`, `orderId`, `startDate`, `endDate`, `timestamp`, and `status`.
- **AC1.4 (Event-driven):** When a valid full `PUT` replacement targets an existing reservation, the service shall replace the five client-assigned detail fields and status, preserve ID and timestamp, and return `200` with persisted values.
- **AC1.5 (Event-driven):** When an existing reservation is deleted, the service shall permanently delete it, return an empty `204`, and return `404` on subsequent retrieval.
- **AC1.6 (Unwanted):** If an item GET, PUT, or DELETE targets a missing valid UUID, then the service shall return `404` without creating a reservation.

### US2 — Browse reservations

As a consumer, I want bounded, deterministic pages of reservations.

- **AC2.1 (Event-driven):** When the collection is requested without parameters, the service shall return `200`, page zero of size 20 sorted by `id ASC`, a non-null `content` array, and nested `page` metadata with size, number, total elements, and total pages.
- **AC2.2 (Event-driven):** When valid page, size, sort, and direction parameters are supplied, the service shall return the requested page, support all eight response fields as primary sorts, cap requested size at 100, and use `id ASC` to break ties for other fields.
- **AC2.3 (Event-driven):** When a valid collection request has no matching page contents, the service shall return `200` with empty content and accurate totals.
- **AC2.4 (Unwanted):** If collection parameters are unknown, repeated, blank, outside bounds, or unsupported, then the service shall return `400` with field violations.

### US3 — Receive validated contracts and consistent failures

As a consumer, I want rejected requests to be predictable and non-mutating.

- **AC3.1 (Unwanted):** If a write omits required fields, has unknown or server-managed properties, malformed JSON, field values that cannot be deserialized or fail validation after Jackson's default scalar-to-string conversion, or endDate earlier than startDate, then the service shall return `400` and preserve existing data.
- **AC3.2 (Event-driven):** When valid dates include a same-day or historical period, the service shall accept the request without future-date, availability, or overlap restrictions.
- **AC3.3 (Unwanted):** If a REST request fails, then the service shall return `application/problem+json` with type, title, status, detail, instance, and code; validation failures shall also include violations sorted by field and message.
- **AC3.4 (Unwanted):** If a request uses unsupported media, an unacceptable response representation, or an unsupported method including PATCH, then the service shall return respectively `415`, `406`, or `405`, preserving applicable protocol headers.
- **AC3.5 (Unwanted):** If an unexpected server error occurs, then the service shall return a fixed sanitized `500` problem without exposing exception text, SQL, credentials, or stack traces to clients.

### US4 — Inspect executable API documentation

As an integrator, I want to discover and exercise the running API.

- **AC4.1 (State-driven):** While the service is running, the service shall expose OpenAPI at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` without credentials.
- **AC4.2 (Ubiquitous):** The OpenAPI contract shall describe all five operations, creation and replacement input fields, response fields including read-only metadata, field formats and constraints, examples, pagination, success codes, and applicable error responses.

### US5 — Initialize and preserve owned data

As an operator, I want repeatable migrations that respect other services' ownership.

- **AC5.1 (Event-driven):** When started with an empty provisioned reservation schema in PostgreSQL 18.4 database rentflow, the service shall apply V1 and validate Hibernate mappings before serving traffic as the reservation role.
- **AC5.2 (Event-driven):** When migrations or the application run again against an intact database, the service shall preserve committed reservations and record each migration once.
- **AC5.3 (Ubiquitous):** The service shall confine all application objects and Flyway history to reservation, use Flyway as the sole application DDL mechanism, and leave objects owned by another service unchanged.
- **AC5.4 (Unwanted):** If database connectivity, schema provisioning, migration, or Hibernate validation fails at startup, then the service shall fail startup before reporting ready.

### US6 — Package, run, and observe the service

As a developer, I want repeatable container startup and useful health reporting.

- **AC6.1 (Event-driven):** When the image is built, the build shall compile in a Java 25 build stage and produce a separate Java 25 runtime image running as UID/GID 10001 with no Maven or compiler.
- **AC6.2 (Event-driven):** When the documented Compose workflow runs, the stack shall start the application and PostgreSQL 18.4, bootstrap only database prerequisites, and retain data in a named volume across recreation.
- **AC6.3 (Ubiquitous):** The service shall obtain deployment database configuration through environment variables without packaged credentials.
- **AC6.4 (State-driven):** While the application and database are healthy, the service shall return `200` and UP on `/livez` and `/readyz`, exposing only health through Actuator and no component details.
- **AC6.5 (Unwanted):** If the database becomes unavailable after startup, then the service shall return `503` on readiness while retaining `200` liveness and recover readiness after the database returns.

### US7 — Develop and verify automatically

As a contributor, I want one verified build and accurate local instructions.

- **AC7.1 (Event-driven):** When `mvn -B -ntp clean verify` runs with the documented prerequisites, the build shall execute compilation, unit tests, real PostgreSQL Testcontainers integration tests, ArchUnit boundaries, and formatting checks without skip flags.
- **AC7.2 (Ubiquitous):** The unit tests shall run without a Spring context and use Mockito for service collaborators; integration tests shall require no manually provisioned database.
- **AC7.3 (Ubiquitous):** The repository shall include pinned Maven Wrapper scripts and a README covering prerequisites, configuration, local/Compose startup and shutdown, shared-database integration, append-only migrations, health, Swagger, CRUD examples, and verification.
- **AC7.4 (Event-driven):** When the container smoke script runs, it shall verify restricted schema ownership, unprivileged runtime, CRUD persistence across application and stack restart, and outage/recovery health behavior, then remove its isolated test containers and volume.
- **AC7.5 (Event-driven):** When API operations are invoked without credentials, the service shall process them according to their functional rules without authentication infrastructure.

## Out of scope

Availability and overlap checks; external Serial Number/Customer ID existence checks; business-key
uniqueness; hold expiry or TTL; confirmation/cancellation workflows and transition guards;
cross-service calls and foreign keys; event publishing; authentication/authorization; PATCH;
soft deletion; filtering; concurrency/versioning/ETags; production infrastructure and CI/CD.

## Resolved questions

These initial decisions use the suggested defaults from the clarification questions and remain
subject to user steering during implementation.

1. Reservation identity is an automatically generated UUID. See `design.md` §2.1.
2. Customer/order IDs are opaque, nonblank strings of at most 64 characters. See `design.md` §2.1.
3. Periods are inclusive calendar dates and may span one day or historical dates. See `design.md` §2.2.
4. Timestamp is immutable server-generated UTC creation time. See `design.md` §2.1.
5. Repeated serial/customer values are accepted; no uniqueness constraint or external check is added. See `design.md` §2.3.
6. PUT requires any of HELD, CONFIRMED, or CANCELLED without transition restrictions. See `design.md` §3.3.
7. DELETE is physical deletion; missing IDs return 404. See `design.md` §3.3.
8. DTOs use `CreateReservationRequest` for POST and `ReservationDTO` for PUT and responses, preserving the existing input restrictions. See `design.md` §2.1 and §4.1.
9. String fields use Jackson's default number/boolean-to-string conversion without a custom mapper configuration; validation applies to the resulting values. See `design.md` §2.2.
