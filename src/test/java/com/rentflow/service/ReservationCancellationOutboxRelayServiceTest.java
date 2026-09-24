package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationCancellationOutbox.FailureCode;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.support.ReservationPropertiesFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCancellationOutboxRelayServiceTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T15:30:00Z");
    private static final Instant ATTEMPT_AT = OCCURRED_AT.plusSeconds(1);

    private ReservationCancellationOutboxRepository outboxes;
    private KafkaTemplate<byte[], byte[]> kafka;
    private ReservationCancellationOutboxRelayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxes = mock(ReservationCancellationOutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        service = new ReservationCancellationOutboxRelayService(
                outboxes, kafka, ReservationPropertiesFixture.runtimeDefaults());
    }

    @Test
    void stopsWhenTheClusterLockIsBusy() {
        when(outboxes.tryRelayLock()).thenReturn(false);

        ReservationCancellationOutboxRelayService.Result result = service.publishOldest();

        assertThat(result.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.LOCK_BUSY);
        verify(outboxes, never()).findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc();
    }

    @Test
    void anIneligibleOldestEventBlocksLaterEvents() {
        ReservationCancellationOutbox outbox = outbox();
        when(outboxes.tryRelayLock()).thenReturn(true);
        when(outboxes.findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc())
                .thenReturn(Optional.of(outbox));
        when(outboxes.databaseTime()).thenReturn(OCCURRED_AT.minusMillis(1));

        ReservationCancellationOutboxRelayService.Result result = service.publishOldest();

        assertThat(result.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.BACKING_OFF);
        verify(kafka, never()).send(any(), any(), any());
    }

    @Test
    void marksPublishedOnlyAfterTheBrokerFutureCompletes() throws Exception {
        ReservationCancellationOutbox outbox = outbox();
        when(outboxes.tryRelayLock()).thenReturn(true);
        when(outboxes.findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc())
                .thenReturn(Optional.of(outbox));
        when(outboxes.databaseTime()).thenReturn(ATTEMPT_AT, ATTEMPT_AT.plusMillis(1));
        when(kafka.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ReservationCancellationOutboxRelayService.Result result = service.publishOldest();

        assertThat(result.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(ATTEMPT_AT.plusMillis(1));
        assertThat(outbox.getAttemptCount()).isOne();
        verify(kafka)
                .send(
                        "rentflow.reservation.cancelled.v1",
                        "Drill-001".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "{\"event\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aTimeoutPersistsBoundedRetryMetadataWithoutChangingTheEvent() {
        ReservationCancellationOutbox outbox = outbox();
        when(outboxes.tryRelayLock()).thenReturn(true);
        when(outboxes.findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc())
                .thenReturn(Optional.of(outbox));
        when(outboxes.databaseTime()).thenReturn(ATTEMPT_AT, ATTEMPT_AT.plusMillis(1));
        when(kafka.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("not persisted")));

        ReservationCancellationOutboxRelayService.Result result = service.publishOldest();

        assertThat(result.status()).isEqualTo(ReservationCancellationOutboxRelayService.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(FailureCode.KAFKA_SEND_TIMEOUT);
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getAttemptCount()).isOne();
        assertThat(outbox.getLastFailureCode()).isEqualTo(FailureCode.KAFKA_SEND_TIMEOUT);
        assertThat(outbox.getRecordKey()).isEqualTo("Drill-001");
        assertThat(outbox.getPayload()).isEqualTo("{\"event\":true}");
        assertThat(outbox.getNextAttemptAt()).isBetween(ATTEMPT_AT.plusMillis(801), ATTEMPT_AT.plusMillis(1201));
    }

    @Test
    void retryDelayGrowsExponentiallyAndSaturatesBeforeJitter() {
        Duration initial = Duration.ofSeconds(1);
        Duration maximum = Duration.ofMinutes(5);

        assertThat(ReservationCancellationOutboxRelayService.retryDelay(initial, 2, maximum, 0.2, 1, 0))
                .isEqualTo(Duration.ofMillis(800));
        assertThat(ReservationCancellationOutboxRelayService.retryDelay(initial, 2, maximum, 0.2, 2, 0.5))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(ReservationCancellationOutboxRelayService.retryDelay(initial, 2, maximum, 0.2, 1000, 1))
                .isEqualTo(Duration.ofMinutes(6));
    }

    private ReservationCancellationOutbox outbox() {
        return new ReservationCancellationOutbox(UUID.randomUUID(), "Drill-001", "{\"event\":true}", OCCURRED_AT);
    }
}
