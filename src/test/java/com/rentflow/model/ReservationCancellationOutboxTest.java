package com.rentflow.model;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rentflow.model.ReservationCancellationOutbox.FailureCode;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationCancellationOutboxTest {

    @Test
    void recordsFailuresAndPublicationWithoutChangingTheEvent() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-21T10:00:00Z");
        ReservationCancellationOutbox outbox =
                new ReservationCancellationOutbox(eventId, "DRILL-001", "{\"event\":true}", occurredAt);
        Instant failedAt = occurredAt.plusSeconds(1);
        Instant nextAttemptAt = occurredAt.plusSeconds(2);

        outbox.recordFailure(failedAt, FailureCode.KAFKA_SEND_TIMEOUT, nextAttemptAt);
        outbox.recordPublished(occurredAt.plusSeconds(3));

        assertThat(outbox.getEventId()).isEqualTo(eventId);
        assertThat(outbox.getRecordKey()).isEqualTo("DRILL-001");
        assertThat(outbox.getPayload()).isEqualTo("{\"event\":true}");
        assertThat(outbox.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getPublishedAt()).isEqualTo(occurredAt.plusSeconds(3));
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(outbox.getLastFailureAt()).isEqualTo(failedAt);
        assertThat(outbox.getLastFailureCode()).isEqualTo(FailureCode.KAFKA_SEND_TIMEOUT);
    }
}
