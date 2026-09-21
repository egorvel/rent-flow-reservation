package com.rentflow.config;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationKafkaConfigTest {

    @Test
    void localTopicUsesTheVersionOnePolicy() {
        ReservationCancellationProperties properties = new ReservationCancellationProperties(
                "rentflow.reservation.cancelled.v1",
                4096,
                new ReservationCancellationProperties.Relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(50)),
                new ReservationCancellationProperties.Retry(Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2),
                new ReservationCancellationProperties.Cleanup("0 30 3 * * *", Duration.ofSeconds(60)));

        NewTopic topic = new ReservationKafkaConfig().reservationCancellationTopic(properties);

        assertThat(topic.name()).isEqualTo("rentflow.reservation.cancelled.v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }
}
