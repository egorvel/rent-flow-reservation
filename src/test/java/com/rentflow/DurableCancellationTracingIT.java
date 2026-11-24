package com.rentflow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.ReservationCancellationOutboxRelayService;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableCancellationTracingIT extends PostgresIntegrationTest {
    private static final String TOPIC = "durable-cancellation-tracing-it";
    private static final AtomicBoolean FAIL_NEXT_COMMIT = new AtomicBoolean();

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Test
    void restartRetainsOriginLinksAndBrokerAcknowledgementDoesNotImplyLocalCommit() throws Exception {
        String originTrace;
        String originSpan;
        String payload;
        UUID eventId;
        SpanData firstAttempt;
        try (ConfigurableApplicationContext first = start()) {
            first.getBean(ReservationCancellationOutboxRepository.class).deleteAllInBatch();
            first.getBean(ReservationRepository.class).deleteAllInBatch();
            Tracer tracer = first.getBean(Tracer.class);
            Span origin = tracer.spanBuilder()
                    .setNoParent()
                    .name("original-cancellation")
                    .start();
            ReservationRepository reservations = first.getBean(ReservationRepository.class);
            LocalDate date = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
            Reservation reservation = reservations.saveAndFlush(new Reservation(
                    "DURABLE-TRACE",
                    "private-customer",
                    com.rentflow.support.SignedJwtFixture.SHARED.issuer(),
                    "private-order",
                    date,
                    date));
            try (Tracer.SpanInScope ignored = tracer.withSpan(origin)) {
                first.getBean(ReservationCancellationService.class).cancel(reservation.getId());
            } finally {
                origin.end();
            }
            originTrace = origin.context().traceId();
            originSpan = origin.context().spanId();
            ReservationCancellationOutboxRepository outboxes =
                    first.getBean(ReservationCancellationOutboxRepository.class);
            ReservationCancellationOutbox outbox = outboxes.findAll().getFirst();
            assertThat(outbox.getTraceparent()).contains(originTrace, originSpan);
            eventId = outbox.getEventId();
            payload = outbox.getPayload();
            FAIL_NEXT_COMMIT.set(true);
            try (EcsLogCapture logs =
                    new EcsLogCapture("com.rentflow.service.ReservationCancellationOutboxRelayService")) {
                assertThatThrownBy(() -> first.getBean(ReservationCancellationOutboxRelayService.class)
                                .publishOldest())
                        .hasMessage("forced publication commit failure");
                assertThat(logs.events())
                        .noneSatisfy(event -> assertThat(event.getFormattedMessage())
                                .isEqualTo("Reservation cancellation publication committed."));
            }
            assertThat(outboxes.findById(eventId).orElseThrow().getPublishedAt())
                    .isNull();
            assertThat(first.getBean(MeterRegistry.class)
                            .find("reservation.cancellation.outbox.publication.delay")
                            .timer())
                    .isNull();
            firstAttempt = attempt(first);
            assertThat(firstAttempt.getAttributes().toString()).contains("acknowledged");
            assertThat(firstAttempt.getLinks()).anySatisfy(link -> {
                assertThat(link.getSpanContext().getTraceId()).isEqualTo(originTrace);
                assertThat(link.getSpanContext().getSpanId()).isEqualTo(originSpan);
            });
        }
        // A wholly new application/SDK/relay instance has only the retained PostgreSQL metadata.
        try (ConfigurableApplicationContext restarted = start()) {
            ReservationCancellationOutboxRepository outboxes =
                    restarted.getBean(ReservationCancellationOutboxRepository.class);
            ObservationRegistry observations = restarted.getBean(ObservationRegistry.class);
            Tracer tracer = restarted.getBean(Tracer.class);
            Observation worker = Observation.start("relay-worker", observations);
            String workerSpan;
            try (Observation.Scope ignored = worker.openScope();
                    EcsLogCapture logs =
                            new EcsLogCapture("com.rentflow.service.ReservationCancellationOutboxRelayService")) {
                workerSpan = tracer.currentSpan().context().spanId();
                assertThat(restarted
                                .getBean(ReservationCancellationOutboxRelayService.class)
                                .publishOldest()
                                .status())
                        .isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
                assertThat(observations.getCurrentObservation()).isSameAs(worker);
                assertThat(tracer.currentSpan().context().spanId()).isEqualTo(workerSpan);
                assertThat(logs.events())
                        .filteredOn(event ->
                                event.getFormattedMessage().equals("Reservation cancellation publication committed."))
                        .singleElement()
                        .satisfies(event -> {
                            tools.jackson.databind.JsonNode json =
                                    EcsLogCapture.encode(event, restarted.getEnvironment());
                            assertThat(json.path("originTraceId").asText()).isEqualTo(originTrace);
                            assertThat(json.toString()).doesNotContain("private-customer", "private-order");
                        });
            } finally {
                worker.stop();
            }
            SpanData secondAttempt = attempt(restarted);
            assertThat(secondAttempt.getParentSpanId()).isEqualTo("0000000000000000");
            assertThat(secondAttempt.getTraceId())
                    .isNotEqualTo(firstAttempt.getTraceId())
                    .isNotEqualTo(originTrace);
            assertThat(secondAttempt.getLinks())
                    .hasSize(2)
                    .anySatisfy(link -> {
                        assertThat(link.getSpanContext().getTraceId()).isEqualTo(originTrace);
                        assertThat(link.getSpanContext().getSpanId()).isEqualTo(originSpan);
                    })
                    .anySatisfy(link ->
                            assertThat(link.getSpanContext().getSpanId()).isEqualTo(workerSpan));
            assertThat(outboxes.findById(eventId).orElseThrow().getPayload()).isEqualTo(payload);
            assertThat(outboxes.findById(eventId).orElseThrow().getPublishedAt())
                    .isNotNull();
            assertThat(restarted
                            .getBean(MeterRegistry.class)
                            .get("reservation.cancellation.outbox.publication.delay")
                            .timer()
                            .count())
                    .isOne();
            List<ConsumerRecord<byte[], byte[]>> records = consume(2);
            assertThat(records).hasSize(2).allSatisfy(record -> {
                assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo(payload);
                assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("DURABLE-TRACE");
                assertThat(record.headers().lastHeader("baggage")).isNull();
            });
            assertThat(new String(
                            records.get(1).headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                    .contains(secondAttempt.getTraceId());
            assertThat(restarted.getBean(InMemorySpanExporter.class).getFinishedSpanItems())
                    .filteredOn(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.PRODUCER)
                    .singleElement()
                    .satisfies(span -> {
                        assertThat(span.getTraceId()).isEqualTo(secondAttempt.getTraceId());
                        assertThat(span.getParentSpanId()).isEqualTo(secondAttempt.getSpanId());
                    });
            org.springframework.kafka.listener.MessageListenerContainer consumer =
                    restarted.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("durable-trace-probe");
            assertThat(consumer).isNotNull();
            consumer.start();
            try {
                assertThat(restarted.getBean(TraceProbe.class).received.await(10, TimeUnit.SECONDS))
                        .isTrue();
            } finally {
                consumer.stop();
            }
            assertThat(restarted
                            .getBean(SdkTracerProvider.class)
                            .forceFlush()
                            .join(5, TimeUnit.SECONDS)
                            .isSuccess())
                    .isTrue();
            assertThat(restarted.getBean(InMemorySpanExporter.class).getFinishedSpanItems())
                    .filteredOn(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER)
                    .hasSize(2)
                    .anySatisfy(span -> assertThat(span.getTraceId()).isEqualTo(secondAttempt.getTraceId()));
        }
    }

    @Test
    void legacyMalformedAndUnsampledOriginsRemainPublishable() {
        String topic = TOPIC + "-legacy";
        try (ConfigurableApplicationContext context = start(topic, "0.0")) {
            ReservationCancellationOutboxRepository outboxes =
                    context.getBean(ReservationCancellationOutboxRepository.class);
            ReservationRepository reservations = context.getBean(ReservationRepository.class);
            outboxes.deleteAllInBatch();
            reservations.deleteAllInBatch();
            Tracer tracer = context.getBean(Tracer.class);
            Span origin =
                    tracer.spanBuilder().setNoParent().name("unsampled-origin").start();
            LocalDate date = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
            Reservation saved = reservations.saveAndFlush(new Reservation(
                    "UNSAMPLED-TRACE",
                    "customer",
                    com.rentflow.support.SignedJwtFixture.SHARED.issuer(),
                    "order",
                    date,
                    date));
            try (Tracer.SpanInScope ignored = tracer.withSpan(origin)) {
                assertThat(origin.context().sampled()).isFalse();
                context.getBean(ReservationCancellationService.class).cancel(saved.getId());
            } finally {
                origin.end();
            }
            ReservationCancellationOutbox captured = outboxes.findAll().getFirst();
            assertThat(captured.getTraceparent())
                    .contains(origin.context().traceId(), origin.context().spanId());
            java.time.Instant now = outboxes.databaseTime();
            outboxes.saveAndFlush(
                    new ReservationCancellationOutbox(UUID.randomUUID(), "LEGACY-TRACE", "{\"legacy\":true}", now));
            outboxes.saveAndFlush(new ReservationCancellationOutbox(
                    UUID.randomUUID(),
                    "MALFORMED-TRACE",
                    "{\"legacy\":false}",
                    now,
                    "unusable-context",
                    "unusable-state"));
            ReservationCancellationOutboxRelayService relay =
                    context.getBean(ReservationCancellationOutboxRelayService.class);
            for (int index = 0; index < 3; index++) {
                assertThat(relay.publishOldest().status())
                        .isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
            }
            assertThat(outboxes.countByPublishedAtIsNull()).isZero();
            assertThat(consume(topic, 3)).hasSize(3).allSatisfy(record -> {
                assertThat(record.headers().lastHeader("traceparent")).isNotNull();
                String parent =
                        new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
                assertThat(Integer.parseInt(parent.substring(parent.lastIndexOf('-') + 1), 16) & 1)
                        .isZero();
            });
            assertThat(context.getBean(SdkTracerProvider.class)
                            .forceFlush()
                            .join(5, TimeUnit.SECONDS)
                            .isSuccess())
                    .isTrue();
            assertThat(context.getBean(InMemorySpanExporter.class).getFinishedSpanItems())
                    .isEmpty();
        }
    }

    private SpanData attempt(ConfigurableApplicationContext context) {
        assertThat(context.getBean(SdkTracerProvider.class)
                        .forceFlush()
                        .join(5, TimeUnit.SECONDS)
                        .isSuccess())
                .isTrue();
        List<SpanData> attempts = context.getBean(InMemorySpanExporter.class).getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("reservation.cancellation.outbox.publish"))
                .toList();
        assertThat(attempts).hasSize(1);
        return attempts.getFirst();
    }

    private ConfigurableApplicationContext start() {
        return start(TOPIC, "1.0");
    }

    private ConfigurableApplicationContext start(String topic, String sampling) {
        return new SpringApplicationBuilder(ReservationApplication.class, Recording.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .initializers(context -> context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new org.springframework.core.env.MapPropertySource(
                                "isolated-security",
                                java.util.Map.of(
                                        "spring.datasource.password", RESERVATION_PASSWORD,
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                                com.rentflow.support.SignedJwtFixture.SHARED.issuer(),
                                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                                                com.rentflow.support.SignedJwtFixture.SHARED.jwks(),
                                        "spring.security.oauth2.client.provider.keycloak.token-uri",
                                                com.rentflow.support.SignedJwtFixture.SHARED.tokenUri(),
                                        "spring.security.oauth2.client.registration.inventory.client-secret",
                                                "isolated-test-only"))))
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + RESERVATION_USERNAME,
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--reservation.cancellation.topic=" + topic,
                        "--management.tracing.sampling.probability=" + sampling,
                        "--spring.kafka.listener.observation-enabled=true",
                        "--spring.kafka.consumer.auto-offset-reset=earliest",
                        "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                        "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer",
                        "--reservation.cancellation.relay.enabled=false",
                        "--reservation.cancellation.expiration.enabled=false",
                        "--reservation.cancellation.cleanup.enabled=false",
                        "--management.server.port=0");
    }

    private List<ConsumerRecord<byte[], byte[]>> consume(int count) {
        return consume(TOPIC, count);
    }

    private List<ConsumerRecord<byte[], byte[]>> consume(String topic, int count) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "durable-trace-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(records::add);
            }
            return records;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableKafka
    static class Recording {
        @Bean
        InMemorySpanExporter exporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        NewTopic topic(
                @org.springframework.beans.factory.annotation.Value("${reservation.cancellation.topic}") String topic) {
            return TopicBuilder.name(topic).partitions(1).replicas(1).build();
        }

        @Bean
        TraceProbe traceProbe() {
            return new TraceProbe();
        }

        @Bean
        KafkaTemplate<byte[], byte[]> kafkaTemplate(
                ProducerFactory<byte[], byte[]> factory, KafkaAdmin admin, ObservationRegistry observations) {
            KafkaTemplate<byte[], byte[]> template = new KafkaTemplate<>(factory) {
                @Override
                public CompletableFuture<SendResult<byte[], byte[]>> send(String topic, byte[] key, byte[] value) {
                    CompletableFuture<SendResult<byte[], byte[]>> future = super.send(topic, key, value);
                    if (FAIL_NEXT_COMMIT.compareAndSet(true, false)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("forced publication commit failure");
                            }
                        });
                    }
                    return future;
                }
            };
            template.setObservationEnabled(true);
            template.setObservationRegistry(observations);
            template.setKafkaAdmin(admin);
            return template;
        }
    }

    static class TraceProbe {
        private final java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(2);

        @KafkaListener(id = "durable-trace-probe", topics = "${reservation.cancellation.topic}", autoStartup = "false")
        void receive(ConsumerRecord<byte[], byte[]> record) {
            received.countDown();
        }
    }
}
