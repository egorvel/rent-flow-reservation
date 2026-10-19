package com.rentflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.service.ReservationCreationService;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(ObservabilityIT.RecordingTraces.class)
class ObservabilityIT extends PostgresIntegrationTest {
    static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @MockitoBean
    private InventoryGateway inventory;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private ReservationCreationService creation;

    @Autowired
    private ReservationCancellationService cancellation;

    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    private io.micrometer.tracing.Tracer tracer;

    @BeforeEach
    void resetSpans() {
        flushSpans();
        SPANS.reset();
    }

    @Test
    void isolatesManagementAndPreservesProbesIdentityAndMeters() throws Exception {
        for (String path : List.of("/actuator/prometheus", "/actuator/health", "/actuator/env")) {
            assertThat(get("local.server.port", path, null).statusCode()).isEqualTo(404);
        }
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/configprops")) {
            assertThat(get("local.management.port", path, null).statusCode()).isEqualTo(404);
        }
        for (String path : List.of("/livez", "/readyz")) {
            assertThat(get("local.server.port", path, null).body()).isEqualTo("{\"status\":\"UP\"}");
        }
        assertThat(get("local.management.port", "/actuator/health/readiness", null)
                        .body())
                .isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("local.server.port", "/api/v1/reservations", null).statusCode())
                .isEqualTo(200);
        assertThat(get("local.management.port", "/actuator/prometheus", null).body())
                .contains(
                        "jvm_memory_used_bytes",
                        "hikaricp_connections",
                        "http_server_requests_seconds_bucket",
                        "service=\"rent-flow-reservation\"",
                        "environment=\"local\"",
                        "resilience4j_circuitbreaker_state");
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.tracing.baggage.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }

    @Test
    void excludesProbesAndScrapesButRetainsUnmatchedBusinessRequests() throws Exception {
        for (String path : List.of("/livez", "/readyz")) {
            get("local.server.port", path, null);
        }
        get("local.management.port", "/actuator/prometheus", null);
        get("local.management.port", "/actuator/health", null);
        get("local.server.port", "/actuator-business", null);
        flushSpans();
        assertThat(SPANS.getFinishedSpanItems())
                .filteredOn(span -> span.getKind() == SpanKind.SERVER)
                .singleElement()
                .satisfies(span -> assertThat(span.getAttributes().toString()).contains("/actuator-business"));
        assertThat(metrics.find("http.server.requests").tag("uri", "/livez").meters())
                .isEmpty();
        assertThat(metrics.find("http.server.requests").tag("uri", "/readyz").meters())
                .isEmpty();
    }

    @Test
    void correlatesJdbcWithoutCapturingRequestInputsOrBoundParameters() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Reservation saved = reservations.saveAndFlush(
                new Reservation("TRACE-JDBC", "private-customer", "private-order", date, date));
        flushSpans();
        SPANS.reset();
        String traceId = "11111111111111111111111111111111";
        assertThat(get(
                                "local.server.port",
                                "/api/v1/reservations/" + saved.getId() + "?secret=private-query",
                                "00-" + traceId + "-2222222222222222-01")
                        .statusCode())
                .isEqualTo(200);
        flushSpans();
        List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).contains("connection"));
        assertThat(spans)
                .anySatisfy(span ->
                        assertThat(span.getAttributes().toString()).contains("jdbc.query", "reservations", "?"));
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.getAttributes().toString() + span.getEvents())
                    .doesNotContain(
                            "private-customer",
                            "private-order",
                            "private-query",
                            "private-header",
                            "private-baggage",
                            "jdbc.params");
            assertThat(span.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("rent-flow-reservation");
        });
        SPANS.reset();
        assertThat(jdbc.queryForObject("SELECT ?::text", String.class, "private-bound-value"))
                .isEqualTo("private-bound-value");
        flushSpans();
        assertThat(SPANS.getFinishedSpanItems())
                .anySatisfy(span -> assertThat(span.getAttributes().toString()).contains("SELECT ?::text"))
                .allSatisfy(span -> assertThat(span.getAttributes().toString())
                        .doesNotContain("private-bound-value", "jdbc.params"));
        assertThat(get("local.management.port", "/actuator/prometheus", null).body())
                .doesNotContain(saved.getId().toString());
        assertThat(metrics.get("http.server.requests")
                        .tag("uri", "/api/v1/reservations/{id}")
                        .timer()
                        .count())
                .isPositive();
    }

    @Test
    void acceptsAbsentAndUnusableTracingContext() throws Exception {
        for (String context : List.of("", "invalid", "00-00000000000000000000000000000000-0000000000000000-01")) {
            assertThat(get("local.server.port", "/api/v1/reservations", context).statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void creationCountersDistinguishCommitReplayRollbackAndCommitPhaseFailure() {
        org.mockito.Mockito.when(
                        inventory.claim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(InventoryGateway.ClaimResult.claimed());
        double before = committedCount();
        java.util.UUID key = java.util.UUID.randomUUID();
        ReservationCreationCommand command = command("COMMITTED-" + key);
        assertThat(creation.create(key, command).status()).isEqualTo(201);
        assertThat(committedCount()).isEqualTo(before + 1);
        assertThat(creation.create(key, command).replayed()).isTrue();
        assertThat(committedCount()).isEqualTo(before + 1);

        org.springframework.transaction.support.TransactionTemplate transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        String rolledBack = "ROLLED-" + java.util.UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            assertThat(creation.create(java.util.UUID.randomUUID(), command(rolledBack))
                            .status())
                    .isEqualTo(201);
            assertThat(committedCount()).isEqualTo(before + 1);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.reservations WHERE serial_number = ?",
                        Long.class,
                        rolledBack))
                .isZero();
        assertThat(committedCount()).isEqualTo(before + 1);

        String commitFailed = "COMMIT-FAIL-" + java.util.UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    creation.create(java.util.UUID.randomUUID(), command(commitFailed));
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw new IllegalStateException("forced commit-phase failure");
                                }
                            });
                }))
                .hasMessage("forced commit-phase failure");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.reservations WHERE serial_number = ?",
                        Long.class,
                        commitFailed))
                .isZero();
        assertThat(committedCount()).isEqualTo(before + 1);
    }

    @Test
    void cancellationPersistsOriginAndLogsOnlyCommittedTransitionsWithEcsDiagnostics() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        Reservation saved = reservations.saveAndFlush(
                new Reservation("TRACE-CANCEL", "private-customer", "private-order", date, date));
        io.micrometer.tracing.Span origin =
                tracer.spanBuilder().setNoParent().name("cancellation-origin").start();
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow.service.ReservationCancellationService");
                io.micrometer.tracing.Tracer.SpanInScope ignored = tracer.withSpan(origin)) {
            cancellation.cancel(saved.getId());
            cancellation.cancel(saved.getId());
            assertThat(outboxes.findAll())
                    .filteredOn(row -> row.getRecordKey().equals("TRACE-CANCEL"))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getTraceparent())
                            .contains(
                                    origin.context().traceId(), origin.context().spanId()));
            assertThat(logs.events()).singleElement().satisfies(event -> {
                tools.jackson.databind.JsonNode json = EcsLogCapture.encode(event, environment);
                assertThat(json.path("outcome").asText()).isEqualTo("cancelled");
                assertThat(json.path("originTraceId").asText())
                        .isEqualTo(origin.context().traceId());
                assertThat(json.path("traceId").asText())
                        .isEqualTo(origin.context().traceId());
                assertThat(json.toString()).doesNotContain("private-customer", "private-order");
            });
        } finally {
            origin.end();
        }
        Reservation rolledBack = reservations.saveAndFlush(
                new Reservation("TRACE-CANCEL-ROLLBACK", "private-customer", "private-order", date, date));
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow.service.ReservationCancellationService")) {
            new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        cancellation.cancel(rolledBack.getId());
                        status.setRollbackOnly();
                    });
            assertThat(logs.events()).isEmpty();
            assertThat(outboxes.findAll())
                    .noneSatisfy(row -> assertThat(row.getRecordKey()).isEqualTo("TRACE-CANCEL-ROLLBACK"));
        }
    }

    private ReservationCreationCommand command(String serial) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        return new ReservationCreationCommand(
                "private-customer", "private-order", List.of(new ReservationCreationCommand.Item(serial, date, date)));
    }

    private double committedCount() {
        return metrics.find("reservation.creation.committed").tag("outcome", "created").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private HttpResponse<String> get(String portProperty, String path, String context) throws Exception {
        int port = environment.getRequiredProperty(portProperty, Integer.class);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer private-header")
                .header("baggage", "secret=private-baggage")
                .GET();
        if (context != null && !context.isEmpty()) {
            request.header("traceparent", context);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void flushSpans() {
        assertThat(tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess())
                .isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTraces {
        @Bean
        SpanExporter recordingSpanExporter() {
            return SPANS;
        }
    }
}
