## Project map

**Domain:**
Reservation service. Owns time-based availability and temporary holds.

**Database topology:**
- RentFlow microservices share the PostgreSQL database `rentflow` at this stage.
- The reservation service connects with the `reservation` role and owns only the `reservation` schema
  and its tables.

**Reservation Entity**
- Reservation ID – unique, auto-generated.
- Serial Number – manually assigned.
- Customer ID – manually assigned.
- Order ID – manually assigned, aggregates all reservations related to a single order.
- Start Date.
- End Date.
- Timestamp.
- Status (HELD, CONFIRMED, CANCELLED).

**Specs:**
- `.specs/<feature>/requirements.md` - what and why. Contain sections Context, User stories with AC, Out of scope, Open/Resolved questions.
- `.specs/<feature>/design.md` - how. API contract, data model, validation rules, invariants, edge cases, integration.
- `.specs/<feature>/tasks.md` - in what order. Decomposition into safe increments with refs to requirements.md and design.md, explicit and testable DoD, dependencies between tasks. Each task: one safe commit.
- spec is the source of truth: gaps go to spec first, code second.
- acceptance criteria must be written in EARS-style.
- Open questions are resolved in `.specs/<feature>/{design,tasks}.md`; `requirements.md` renames `## Open questions` → `## Resolved questions` with each resolution inlined and a pointer to the file where the decision is justified.
- **Always ask 5–7 clarifying questions** before creating or updating any of `.specs/<feature>/{requirements,design,tasks}.md`. One decision = one question. If there is no real doubt, do not invent one.

## Invariants

- **Before declaring done: run `mvn -B -ntp clean verify` locally and confirm `BUILD SUCCESS`.** Do not split this into "just the tests" or "just compile". If Spotless fails, run `mvn spotless:apply` and re-run `verify`. Never skip with `-DskipTests`, `-Dspotless.check.skip`, or similar flags.
- Use explicit Java types for local variables and enhanced `for` loops; do not use `var`.
- Prefer existing project patterns over new abstractions. Before adding a new abstraction, dependency, folder, framework, or test style, search for an existing equivalent in the repo.
- Make the smallest change that correctly solves the task. Do not refactor unrelated code, reformat entire files, rename public APIs, or clean up nearby code unless the task explicitly asks for it.
- Do not make tests pass by weakening assertions, deleting tests, ignoring exceptions, increasing timeouts blindly, or suppressing errors. If a test is wrong, explain why and update it to assert the correct behavior.
- Never print, copy, commit, or expose secrets.

## Architectural rules

**Technology requirements:**
- Java 25, Spring Boot 4, Maven
- PostgreSQL 18.4, Flyway
- Spring Data JPA (Hibernate) for persistence
- Testcontainers

**Persistence conventions:**
- The `rentflow` database, `reservation` login role, and role-owned `reservation` schema are
  platform-provisioned prerequisites; the local Compose bootstrap may provide them for
  development.
- Reservation migrations and Flyway history are confined to the `reservation` schema; they must not
  modify objects owned by another RentFlow service.
- Changes to Reservation-owned tables, indexes, constraints, and other application objects go
  through Flyway migrations (`src/main/resources/db/migration/V{n}__description.sql` or
  `src/main/java/com/rentflow/db/migration/V{n}__description.java`).
- Migrations are append-only too: never edit a shipped migration — add a new one.

**Testing conventions:**
- Unit tests – no Spring context.
- Use Mockito for mocks.
- Integration tests with Testcontainers (real DBs).
- ArchUnit layer boundary test based on the **Layout**.

**Layout**
- Java configs: : `src/main/java/com/rentflow/config`
- Controllers: `src/main/java/com/rentflow/controller`
- Converters: `src/main/java/com/rentflow/converter`
- DTOs: `src/main/java/com/rentflow/dto`
- Services: `src/main/java/com/rentflow/service`
- Repositories: `src/main/java/com/rentflow/repository`
- Entities: `src/main/java/com/rentflow/model`
- Utils: `src/main/java/com/rentflow/util`
- Specs: `.specs/<feature>/{requirements,design,tasks}.md`
