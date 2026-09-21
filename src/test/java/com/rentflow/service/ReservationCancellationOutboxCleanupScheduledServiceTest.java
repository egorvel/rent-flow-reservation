package com.rentflow.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCancellationOutboxCleanupScheduledServiceTest {
    private ReservationCancellationOutboxCleanupService cleanup;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        cleanup = mock(ReservationCancellationOutboxCleanupService.class);
        metrics = new SimpleMeterRegistry();
    }

    @Test
    void continuesAcrossAFullChunkAndRefreshesTheBacklog() {
        when(cleanup.deleteChunk()).thenReturn(1000, 7);
        when(cleanup.countBacklog()).thenReturn(3L);
        ReservationCancellationOutboxCleanupScheduledService scheduler =
                new ReservationCancellationOutboxCleanupScheduledService(cleanup, metrics, Duration.ofSeconds(60));

        scheduler.clean();

        verify(cleanup, times(2)).deleteChunk();
        assertThat(metrics.counter("reservation.cancellation.outbox.cleanup.deleted")
                        .count())
                .isEqualTo(1007);
        assertThat(metrics.find("reservation.cancellation.outbox.cleanup.backlog")
                        .gauge()
                        .value())
                .isEqualTo(3);
    }

    @Test
    void recordsFailureAndLeavesRetryToTheNextSchedule() {
        when(cleanup.deleteChunk()).thenThrow(new IllegalStateException("not logged"));
        ReservationCancellationOutboxCleanupScheduledService scheduler =
                new ReservationCancellationOutboxCleanupScheduledService(cleanup, metrics, Duration.ofSeconds(60));

        scheduler.clean();

        assertThat(metrics.counter("reservation.cancellation.outbox.cleanup.failures")
                        .count())
                .isEqualTo(1);
    }
}
