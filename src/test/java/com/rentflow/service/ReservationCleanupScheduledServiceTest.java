package com.rentflow.service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCleanupScheduledServiceTest {
    private ReservationCleanupService cleanup;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        cleanup = mock(ReservationCleanupService.class);
        metrics = new SimpleMeterRegistry();
    }

    @Test
    void schedulesUseTheConfiguredCronValues() throws Exception {
        Method creation = ReservationCleanupScheduledService.class.getMethod("cleanCreationRequests");
        Method cancellation = ReservationCleanupScheduledService.class.getMethod("cleanCancellationOutbox");

        assertThat(creation.getAnnotation(Scheduled.class).cron()).isEqualTo("${reservation.creation.cleanup.cron}");
        assertThat(cancellation.getAnnotation(Scheduled.class).cron())
                .isEqualTo("${reservation.cancellation.cleanup.cron}");
    }

    @Test
    void creationCleanupContinuesAcrossAFullChunkAndRefreshesTheBacklog() {
        when(cleanup.deleteExpiredCreationChunk()).thenReturn(1000, 7);
        when(cleanup.countExpiredCreationRequests()).thenReturn(3L);

        scheduler(true, () -> 0L).cleanCreationRequests();

        verify(cleanup, times(2)).deleteExpiredCreationChunk();
        assertThat(metrics.counter("reservation.creation.cleanup.deleted").count())
                .isEqualTo(1007);
        assertThat(metrics.get("reservation.creation.cleanup.expired.backlog")
                        .gauge()
                        .value())
                .isEqualTo(3);
    }

    @Test
    void cancellationCleanupUsesTheSameBoundedLoopAndMetrics() {
        when(cleanup.deletePublishedCancellationChunk()).thenReturn(1000, 5);
        when(cleanup.countCancellationCleanupBacklog()).thenReturn(2L);

        scheduler(true, () -> 0L).cleanCancellationOutbox();

        verify(cleanup, times(2)).deletePublishedCancellationChunk();
        assertThat(metrics.counter("reservation.cancellation.outbox.cleanup.deleted")
                        .count())
                .isEqualTo(1005);
        assertThat(metrics.get("reservation.cancellation.outbox.cleanup.backlog")
                        .gauge()
                        .value())
                .isEqualTo(2);
    }

    @Test
    void runtimeBudgetStopsAnotherFullChunk() {
        LongSupplier nanoTime = mock(LongSupplier.class);
        when(nanoTime.getAsLong()).thenReturn(0L, Duration.ofSeconds(60).toNanos());
        when(cleanup.deleteExpiredCreationChunk()).thenReturn(1000);

        scheduler(true, nanoTime).cleanCreationRequests();

        verify(cleanup).deleteExpiredCreationChunk();
        verify(cleanup).countExpiredCreationRequests();
    }

    @Test
    void disabledCancellationCleanupDoesNoWorkOrMetricRegistration() {
        ReservationCleanupScheduledService scheduler = scheduler(false, () -> 0L);

        scheduler.cleanCancellationOutbox();

        verify(cleanup, never()).deletePublishedCancellationChunk();
        verify(cleanup, never()).countCancellationCleanupBacklog();
        assertThat(metrics.find("reservation.cancellation.outbox.cleanup.backlog")
                        .gauge())
                .isNull();
    }

    @Test
    void failuresUseEachJobsExistingMetric() {
        when(cleanup.deleteExpiredCreationChunk()).thenThrow(new IllegalStateException("not logged"));
        when(cleanup.deletePublishedCancellationChunk()).thenThrow(new IllegalStateException("not logged"));
        ReservationCleanupScheduledService scheduler = scheduler(true, () -> 0L);

        scheduler.cleanCreationRequests();
        scheduler.cleanCancellationOutbox();

        assertThat(metrics.counter("reservation.creation.cleanup.failures").count())
                .isOne();
        assertThat(metrics.counter("reservation.cancellation.outbox.cleanup.failures")
                        .count())
                .isOne();
    }

    private ReservationCleanupScheduledService scheduler(boolean cancellationEnabled, LongSupplier nanoTime) {
        return new ReservationCleanupScheduledService(
                cleanup, metrics, Duration.ofSeconds(60), Duration.ofSeconds(60), cancellationEnabled, nanoTime);
    }
}
