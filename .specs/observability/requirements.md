# Reservation observability requirements

Status: Implemented on 2026-10-17 following the implementation request; final acceptance is recorded in design.md §9.

## Context

Reservation owns application instrumentation; Common owns collection, storage, dashboards,
alerts and cross-service verification. This feature applies the shared
[observability plan](../../../OBSERVABILITY_PLAN.md) and the established
[Inventory](../../../rent-flow-inventory/.specs/observability/design.md) and
[Pricing](../../../rent-flow-pricing/.specs/observability/design.md) conventions.

Actuator health, creation idempotency counters, Resilience4j metrics, outbox publication metrics,
hold-expiration metrics and cleanup metrics already exist. Prometheus export, structured JSON
logging, distributed traces and durable outbox trace context do not. Backlog refresh depends on
workers, initial zero values can imply health before measurement, and worker WARNs omit exceptions.

Cancellation HTTP 204 confirms the local transaction, not Kafka publication or Inventory release.
Operators need to distinguish these stages and connect them across retries and process restarts.

## User stories with acceptance criteria

### US1 — Diagnose availability and latency

As an operator, I need private metrics and correlated request/database traces.

- **AC1.1 (Ubiquitous):** The service shall expose only health and Prometheus on a configurable management listener, default port 8087 bound to loopback for host execution, without publishing that port on the host in Compose.
- **AC1.2 (Ubiquitous):** The service shall preserve application-port liveness/readiness probes, hidden health details, database-only readiness, business/OpenAPI contracts and existing transaction, retry, locking and delivery semantics.
- **AC1.3 (Ubiquitous):** The service shall identify telemetry by service, environment and runtime instance, using bounded metric/Loki labels and excluding business identifiers, trace IDs and raw URLs from those labels.
- **AC1.4 (Event-driven):** When a business HTTP request performs database or Inventory work, the service shall produce correlated request and applicable JDBC connection/query or HTTP client spans, HTTP latency histograms and existing JVM/pool metrics.
- **AC1.5 (Unwanted):** If a trace collector is absent, slow or unable to accept queued spans, then the service shall preserve business responses and readiness independently of bounded asynchronous export, with trace export disabled by default.
- **AC1.6 (Event-driven):** When an HTTP request carries absent or unusable W3C context, the service shall continue processing using framework context handling without business rejection or propagation of baggage.

### US2 — Explain reservation creation and Inventory claims

As an operator, I need to distinguish execution, committed creation results and dependency behavior.

- **AC2.1 (Ubiquitous):** The service shall retain the names and counting meanings of existing creation idempotency, Resilience4j, outbox publication, expiration and cleanup meters.
- **AC2.2 (Event-driven):** When a newly recorded terminal creation outcome commits, the service shall increment one bounded committed-outcome counter, excluding replays, transient busy results, local key mismatches and rolled-back transactions.
- **AC2.3 (Event-driven):** When creation service execution completes or fails, the service shall observe its execution duration and bounded outcome and record submitted batch size once per invocation, including replays and failures, without claiming method return means commit success.
- **AC2.4 (Event-driven):** When Reservation attempts an Inventory claim, the service shall observe one logical claim including circuit evaluation, all physical attempts and retry waiting, propagate valid W3C context to each physical request, and distinguish business rejection, dependency/protocol failure and circuit rejection.
- **AC2.5 (Event-driven):** When an Inventory retry is scheduled or its circuit changes state, the service shall emit structured diagnostics and a retry observation event where a logical claim is active, without changing resilience policy or initiating extra dependency calls.

### US3 — Investigate durable cancellation delivery

As an operator, I need cancellation correlation that survives delays, retries and restarts.

- **AC3.1 (Event-driven):** When a cancellation or timed expiration creates an outbox event, the service shall persist available originating W3C trace context atomically with the event as optional Reservation-owned metadata, while keeping existing rows without usable context processable.
- **AC3.2 (Event-driven):** When a due outbox event is sent, the service shall create a separate publication-attempt trace linked to valid stored origin context and any active worker context, with a child observed Kafka send whose context propagates to Inventory; retries shall retain the same origin link across process restarts.
- **AC3.3 (Ubiquitous):** The service shall preserve cancellation payload and key bytes, version-1 event fields, FIFO selection, backoff, at-least-once delivery, commit boundaries and interruption handling while adding framework Kafka tracing headers.
- **AC3.4 (Event-driven):** When a cancellation transition or acknowledged publication commits, the service shall emit one structured INFO result record for that committed transition or publication, excluding rolled-back work and already-cancelled no-ops and retaining correlation fields without customer/order data.
- **AC3.5 (Event-driven):** When publication succeeds, fails, times out, is interrupted or rolls back after broker acknowledgement, the service shall expose the applicable attempt outcome, exception diagnostics and committed-publication delay without reporting broker acknowledgement as committed delivery or Inventory release.

### US4 — Detect stalled workers and stale measurements

As an operator, I need backlog signals that remain useful when a worker stops making progress.

- **AC4.1 (Event-driven):** When independent monitoring refreshes execute, the service shall measure pending outbox count/oldest age and overdue hold count/oldest age every configurable 15 seconds and both cleanup backlogs every configurable 60 seconds by default, using database-time semantics and database work bounded to five seconds per backlog snapshot, without querying during scrapes.
- **AC4.2 (Unwanted):** If a backlog refresh fails, then the service shall retain the last successful snapshot and measurement timestamp, count the refresh failure and log its exception; before the first successful refresh the backlog shall be unknown.
- **AC4.3 (Event-driven):** When a relay, expiration or cleanup worker runs, the service shall expose run/success timestamps, duration and a successful or failed worker observation, including failures caught for subsequent scheduled retry, without changing business schedules, chunk transactions or budgets.
- **AC4.4 (Ubiquitous):** The service shall continue backlog monitoring when relay, expiration or cancellation cleanup execution is disabled.

### US5 — Retain useful diagnostics and provide a verifiable handoff

As an implementer, I need consistent diagnostics, actionable signals and repeatable acceptance.

- **AC5.1 (Ubiquitous):** The service shall emit ECS JSON to stdout with available trace/span correlation and ordinary exception messages, causes, stacks and SQL statement text, while keeping automatic body/header/query-string and bound SQL parameter capture disabled and excluding credentials, customer/order fields, complete Kafka payloads and raw keys from application-defined diagnostic fields.
- **AC5.2 (Ubiquitous):** The service shall document the scrape/export/log contract, bounded labels, investigation paths and provisional alerts for publication age above two minutes and overdue holds above 30 seconds, with measurement-freshness interpretation and a separate Common integration handoff.
- **AC5.3 (Ubiquitous):** The implementation shall pass Java 25 Maven clean verify and the existing Reservation container smoke test extended for management isolation, with regression coverage for commit accounting, restart correlation, legacy context, propagation, telemetry outages and diagnostic capture controls.

## Out of scope

Common infrastructure/dashboard/alert changes; production authentication/TLS, retention and
SLO selection; an OpenTelemetry agent or
second SDK; Inventory/Pricing changes; cancellation acknowledgement events or a new release-status
API; business correctness changes, transaction-boundary changes and resilience-policy tuning;
universal text redaction, blanket telemetry allowlists and SQL literal parsing. Existing schema
migrations remain append-only. Only optional outbox context metadata requires a new migration.

## Resolved questions

1. Preserve existing execution counters and add committed terminal-creation outcome accounting;
   replays and rollbacks do not inflate committed counts. See design.md §3 and tasks.md T2.
2. Use separate publication-attempt traces linked to the durable originating context and worker
   context; never keep a span open while an event waits in PostgreSQL. See design.md §5.
3. Independently refresh operational backlogs every 15 seconds and cleanup backlogs every
   60 seconds by default, including disabled workers, with freshness metadata. See design.md §6.
4. Write INFO results for each committed cancellation transition and committed publication;
   use metrics/traces for ordinary creation success. See design.md §5 and §7.
5. Adopt ordinary Inventory/Pricing exception and SQL diagnostics with collection controls;
   deliberately captured customer/order data and high-cardinality labels remain excluded.
   See design.md §7.
6. Use provisional warning thresholds of two minutes for oldest pending publication and
   30 seconds for oldest overdue hold; Common provisions rules and evaluates actual release
   separately using Inventory completion delay. See design.md §8.
