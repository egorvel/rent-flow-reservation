package com.rentflow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.ReservationCancellationOutboxRelayService;
import com.rentflow.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.tracing.export.otlp.enabled=true",
            "management.opentelemetry.tracing.export.max-queue-size=1",
            "management.opentelemetry.tracing.export.max-batch-size=1",
            "management.opentelemetry.tracing.export.schedule-delay=10ms"
        })
@AutoConfigureTracing(export = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ObservabilityExporterUnavailableIT extends PostgresIntegrationTest {
    private static final CountDownLatch RECEIVED = new CountDownLatch(1);
    private static final CountDownLatch FINISH = new CountDownLatch(1);
    private static final AtomicReference<String> PAYLOAD = new AtomicReference<>();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer COLLECTOR = startCollector();

    @DynamicPropertySource
    static void dependencyProperties(DynamicPropertyRegistry properties) {
        properties.add(
                "management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/v1/traces");
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ReservationRepository repository;

    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private ReservationCancellationOutboxRelayService relay;

    @Autowired
    private tools.jackson.databind.ObjectMapper mapper;

    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<byte[], byte[]> kafka;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private SpanExporter exporter;

    @MockitoBean
    private InventoryGateway inventory;

    @AfterAll
    static void stopCollector() {
        FINISH.countDown();
        COLLECTOR.stop(0);
        WORKERS.close();
    }

    @Test
    void delayedOverflowingAndRefusedExportPreservesReadsWritesAndReadiness() throws Exception {
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(environment.getProperty(
                        "management.opentelemetry.tracing.export.otlp.connect-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(2));
        when(inventory.claim(any(), any())).thenReturn(InventoryGateway.ClaimResult.claimed());
        when(kafka.send(any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        io.opentelemetry.api.trace.Tracer tracer = tracerProvider.get("collector-outage-test");
        tracer.spanBuilder("start-export").startSpan().end();
        assertThat(RECEIVED.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(PAYLOAD.get()).contains("service.name", "rent-flow-reservation");

        CountDownLatch dropped = new CountDownLatch(1);
        Logger batchLogger = Logger.getLogger("io.opentelemetry.sdk.trace.export.BatchSpanProcessor");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().startsWith("BatchSpanProcessor dropped ")) {
                    dropped.countDown();
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        batchLogger.addHandler(capture);
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int index = 0; index < 1000; index++) {
                tracer.spanBuilder("queue-pressure").startSpan().end();
            }
            verifyBusinessWork(client, "EXPORTER-DELAYED");
            assertThat(FINISH.getCount()).isOne();
            FINISH.countDown();
            assertThat(dropped.await(5, TimeUnit.SECONDS)).isTrue();
            COLLECTOR.stop(0);

            io.opentelemetry.api.trace.Span refused =
                    tracer.spanBuilder("refused-export").startSpan();
            refused.end();
            assertThat(exporter.export(List.of(((ReadableSpan) refused).toSpanData()))
                            .join(5, TimeUnit.SECONDS)
                            .isSuccess())
                    .isFalse();
            verifyBusinessWork(client, "EXPORTER-REFUSED");
        } finally {
            batchLogger.removeHandler(capture);
            FINISH.countDown();
        }
    }

    private void verifyBusinessWork(HttpClient client, String serial) throws Exception {
        java.time.LocalDate date =
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        String body = """
                {"orderId":"private-order","items":[
                 {"serialNumber":"%s","startDate":"%s","endDate":"%s"}]}
                """.formatted(serial, date, date);
        HttpResponse<String> created = request(client, "/api/v1/reservations", "POST", body);
        assertThat(created.statusCode()).isEqualTo(201);
        java.util.UUID id = java.util.UUID.fromString(
                mapper.readTree(created.body()).get(0).path("id").asString());
        assertThat(repository.findById(id)).isPresent();
        assertThat(request(client, "/api/v1/reservations/" + id, "GET", null).statusCode())
                .isEqualTo(200);
        assertThat(request(client, "/api/v1/reservations/" + id + "/cancel", "POST", null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(outboxes.countByPublishedAtIsNull()).isPositive();
        assertThat(relay.publishOldest().status())
                .isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
        assertThat(request(client, "/readyz", "GET", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> request(HttpClient client, String path, String method, String body) throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        // Shorter than the unchanged 2s exporter timeout: synchronous delivery would fail this bound.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer " + com.rentflow.support.SignedJwtFixture.SHARED.staff())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/v1/traces", exchange -> {
                PAYLOAD.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                RECEIVED.countDown();
                try {
                    FINISH.await(10, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(503, -1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start test collector", exception);
        }
    }
}
