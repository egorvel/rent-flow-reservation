package com.rentflow;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.rentflow.service.InventoryGateway;
import com.rentflow.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "resilience4j.retry.instances.inventory.wait-duration=1ms",
            "resilience4j.retry.instances.inventory.exponential-max-wait-duration=8ms"
        })
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(InventoryObservabilityIT.RecordingTraces.class)
class InventoryObservabilityIT extends PostgresIntegrationTest {
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
    private static final Queue<Integer> RESPONSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> BODIES = new ConcurrentLinkedQueue<>();
    private static final Queue<List<String>> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = server();

    @DynamicPropertySource
    static void inventoryProperties(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.http.serviceclient.inventory.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Autowired
    private InventoryGateway inventory;

    @Autowired
    private Tracer tracer;

    @Autowired
    private SdkTracerProvider provider;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void clear() {
        breakers.circuitBreaker("inventory").reset();
        flush();
        SPANS.reset();
        REQUESTS.clear();
        RESPONSES.clear();
        BODIES.clear();
    }

    @AfterAll
    static void close() {
        SERVER.stop(0);
        WORKERS.close();
    }

    @Test
    void logicalClaimIncludesRetriesAndPropagatesContextAndStableIdempotencyKey() {
        RESPONSES.addAll(List.of(503, 504, 204));
        UUID key = UUID.randomUUID();
        Span parent = tracer.spanBuilder().setNoParent().name("caller").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            assertThat(inventory.claim(key, List.of("TRACE-CLAIM")).type())
                    .isEqualTo(InventoryGateway.ClaimResult.Type.CLAIMED);
            assertThat(tracer.currentSpan().context().spanId())
                    .isEqualTo(parent.context().spanId());
        } finally {
            parent.end();
        }
        flush();
        SpanData logical = logical();
        assertThat(logical.getTraceId()).isEqualTo(parent.context().traceId());
        assertThat(logical.getEvents())
                .filteredOn(event -> event.getName().equals("inventory.retry"))
                .hasSize(2);
        assertThat(REQUESTS).hasSize(3).allSatisfy(request -> {
            assertThat(request.get(0)).startsWith("00-" + parent.context().traceId() + "-");
            assertThat(request.get(1)).isEqualTo(key.toString());
            assertThat(request.get(2)).isEmpty();
        });
        assertThat(SPANS.getFinishedSpanItems())
                .filteredOn(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT
                        && span.getTraceId().equals(logical.getTraceId()))
                .hasSize(3)
                .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(logical.getSpanId()));
        assertThat(metrics.get("reservation.inventory.claim")
                        .tag("outcome", "claimed")
                        .timer()
                        .count())
                .isPositive();
    }

    @Test
    void shortCircuitRecordsLogicalFailureWithoutPhysicalRequests() {
        breakers.circuitBreaker("inventory").transitionToOpenState();
        assertThatThrownBy(() -> inventory.claim(UUID.randomUUID(), List.of("TRACE-OPEN")))
                .isInstanceOf(InventoryGateway.ServiceUnavailableException.class);
        flush();
        assertThat(logical().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(logical().getAttributes().toString()).contains("short_circuited");
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void protocolFailureIsNotRetriedAndScopesRestore() {
        RESPONSES.add(200);
        assertThatThrownBy(() -> inventory.claim(UUID.randomUUID(), List.of("TRACE-PROTOCOL")))
                .isInstanceOf(InventoryGateway.ProtocolException.class);
        flush();
        assertThat(logical().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(logical().getAttributes().toString()).contains("protocol_error");
        assertThat(REQUESTS).hasSize(1);
        assertThat(tracer.currentSpan()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "404,INVENTORY_ITEM_NOT_FOUND,missing", "409,INVALID_INVENTORY_STATUS_TRANSITION,unavailable_items",
        "400,VALIDATION_FAILED,invalid_reference", "422,IDEMPOTENCY_KEY_REUSED,key_reused",
        "409,IDEMPOTENCY_IN_PROGRESS,busy"
    })
    void decodedBusinessRejectionsHaveBoundedOutcomesAndNoTechnicalFailureLogs(
            int status, String code, String outcome) {
        RESPONSES.add(status);
        BODIES.add("""
                {"code":"%s","failedItems":[{"index":0,"serialNumber":"TRACE-REJECTED","code":"%s"}],
                 "violations":[{"field":"[0].serialNumber","message":"invalid reference"}]}
                """.formatted(code, code));
        try (com.rentflow.support.EcsLogCapture logs =
                new com.rentflow.support.EcsLogCapture("com.rentflow.service.rest.RestInventoryService")) {
            inventory.claim(UUID.randomUUID(), List.of("TRACE-REJECTED"));
            flush();
            assertThat(logical().getAttributes().toString()).contains("outcome=\"" + outcome + "\"");
            assertThat(logical().getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
            assertThat(logs.events()).isEmpty();
            assertThat(REQUESTS).hasSize(1);
        }
    }

    private SpanData logical() {
        List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("reservation.inventory.claim"))
                .toList();
        assertThat(spans).hasSize(1);
        return spans.getFirst();
    }

    private void flush() {
        assertThat(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", exchange -> {
                REQUESTS.add(List.of(
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("traceparent"), ""),
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Idempotency-Key"), ""),
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("baggage"), "")));
                Integer response = RESPONSES.poll();
                exchange.getRequestBody().readAllBytes();
                String body = BODIES.poll();
                byte[] content = body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (content != null) {
                    exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
                }
                exchange.sendResponseHeaders(response == null ? 500 : response, content == null ? -1 : content.length);
                if (content != null) {
                    exchange.getResponseBody().write(content);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTraces {
        @Bean
        SpanExporter recordingInventoryExporter() {
            return SPANS;
        }
    }
}
