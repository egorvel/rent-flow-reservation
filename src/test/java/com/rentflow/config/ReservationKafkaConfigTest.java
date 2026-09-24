package com.rentflow.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

import com.rentflow.support.ReservationPropertiesFixture;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationKafkaConfigTest {

    @Test
    void localTopicUsesTheVersionOnePolicy() {
        ReservationProperties properties = ReservationPropertiesFixture.defaults();

        NewTopic topic = new ReservationKafkaConfig().reservationCancellationTopic(properties);

        assertThat(topic.name()).isEqualTo("rentflow.reservation.cancelled.v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }
}
