package com.rentflow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.ReservationCancellationOutboxRelayService;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "reservation.cancellation.topic=reservation-cancellation-source-it",
            "reservation.cancellation.relay.send-timeout=5s",
            "reservation.cancellation.retry.initial-backoff=10ms",
            "reservation.cancellation.retry.max-backoff=100ms",
            "reservation.cancellation.retry.jitter=0"
        })
@Import(ReservationCancellationKafkaIT.KafkaTopic.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationCancellationKafkaIT extends PostgresIntegrationTest {
    private static final String TOPIC = "reservation-cancellation-source-it";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private ReservationCancellationService cancellation;

    @Autowired
    private ReservationCancellationOutboxRelayService relay;

    @Autowired
    private DefaultKafkaProducerFactory<byte[], byte[]> producerFactory;

    @BeforeEach
    void clearDatabase() {
        outboxes.deleteAllInBatch();
        reservations.deleteAllInBatch();
    }

    @Test
    void publishesPersistedBytesInSameSerialOrderAndMarksAcknowledged() throws Exception {
        Reservation first = reservations.saveAndFlush(reservation("ORDER-001"));
        cancellation.cancel(first.getId());
        Reservation second = reservations.saveAndFlush(reservation("ORDER-002"));
        cancellation.cancel(second.getId());
        List<ReservationCancellationOutbox> expected = outboxes.findAll(Sort.by("occurredAt", "eventId"));

        ReservationCancellationOutboxRelayService.Result firstResult = relay.publishOldest();
        ReservationCancellationOutboxRelayService.Result secondResult = relay.publishOldest();

        assertThat(firstResult.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
        assertThat(secondResult.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
        List<ConsumerRecord<byte[], byte[]>> records = consume(2);
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> {
            assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("KAFKA-ORDER");
            assertThat(record.headers().toArray()).extracting(Header::key).isEmpty();
        });
        assertThat(records)
                .extracting(ConsumerRecord::partition)
                .containsOnly(records.getFirst().partition());
        assertThat(records).extracting(ConsumerRecord::offset).isSorted();
        assertThat(records)
                .extracting(record -> new String(record.value(), StandardCharsets.UTF_8))
                .containsExactly(expected.get(0).getPayload(), expected.get(1).getPayload());
        assertThat(outboxes.findAll()).allSatisfy(outbox -> {
            assertThat(outbox.getPublishedAt()).isNotNull();
            assertThat(outbox.getNextAttemptAt()).isNull();
            assertThat(outbox.getAttemptCount()).isOne();
        });
        Map<String, Object> producer = producerFactory.getConfigurationProperties();
        assertThat(producer.get(ProducerConfig.ACKS_CONFIG).toString()).isEqualTo("all");
        assertThat(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG).toString())
                .isEqualTo("true");
        assertThat(producer.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)
                        .toString())
                .isEqualTo("5");
        assertThat(producer.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG).toString())
                .isEqualTo("30000");
        assertThat(producer.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG).toString())
                .isEqualTo("45000");
        assertThat(producer.get(ProducerConfig.MAX_BLOCK_MS_CONFIG).toString()).isEqualTo("5000");
        assertTopicPolicy();
    }

    @Test
    void publishesAutomaticExpirationWithTheExistingVersionOneContract() throws Exception {
        Instant databaseTime = reservations.databaseTime();
        Reservation reservation = new Reservation(
                "KAFKA-EXPIRED",
                "CUSTOMER-001",
                "ORDER-EXPIRED",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 3),
                databaseTime.minus(Duration.ofMinutes(11)),
                databaseTime.minus(Duration.ofMinutes(1)));
        reservations.saveAndFlush(reservation);

        assertThat(cancellation.expireOldestHeld()).isEqualTo(ReservationCancellationService.ExpirationResult.EXPIRED);
        ReservationCancellationOutbox expected = outboxes.findAll().getFirst();
        assertThat(relay.publishOldest().status())
                .isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);

        ConsumerRecord<byte[], byte[]> record = consumeForKey("KAFKA-EXPIRED");
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo(expected.getPayload());
        assertThat(new String(record.value(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"ReservationCancelled\"", "\"eventVersion\":1")
                .doesNotContain("reason", "reservationId");
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    private Reservation reservation(String orderId) {
        return new Reservation(
                "KAFKA-ORDER", "CUSTOMER-001", orderId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
    }

    private List<ConsumerRecord<byte[], byte[]>> consume(int expectedCount) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "reservation-cancellation-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<byte[], byte[]>> received = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Set.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (received.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                records.forEach(received::add);
            }
        }
        return received;
    }

    private ConsumerRecord<byte[], byte[]> consumeForKey(String expectedKey) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "reservation-cancellation-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Set.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (new String(record.key(), StandardCharsets.UTF_8).equals(expectedKey)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Kafka record was not received for key " + expectedKey);
    }

    private void assertTopicPolicy() throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            assertThat(admin.describeTopics(Set.of(TOPIC))
                            .allTopicNames()
                            .get(5, TimeUnit.SECONDS)
                            .get(TOPIC)
                            .partitions())
                    .hasSize(3);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC);
            Config config = admin.describeConfigs(Set.of(resource))
                    .all()
                    .get(5, TimeUnit.SECONDS)
                    .get(resource);
            assertThat(value(config, TopicConfig.CLEANUP_POLICY_CONFIG)).isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
            assertThat(value(config, TopicConfig.RETENTION_MS_CONFIG)).isEqualTo("604800000");
        }
    }

    private String value(Config config, String name) {
        ConfigEntry entry = config.get(name);
        assertThat(entry).as(name).isNotNull();
        return entry.value();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopic {
        @Bean
        NewTopic cancellationSourceTopic() {
            return TopicBuilder.name(TOPIC)
                    .partitions(3)
                    .replicas(1)
                    .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                    .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                    .build();
        }
    }
}
