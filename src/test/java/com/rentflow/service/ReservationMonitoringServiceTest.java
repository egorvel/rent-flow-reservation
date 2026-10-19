package com.rentflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.rentflow.model.ReservationBacklog;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.support.ReservationPropertiesFixture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationMonitoringServiceTest {
    @Test
    void retainsUnknownOrLastSuccessfulSnapshotsWithoutQueriesOnScrape() {
        ReservationCreationRequestRepository creation = mock(ReservationCreationRequestRepository.class);
        ReservationCancellationOutboxRepository outboxes = mock(ReservationCancellationOutboxRepository.class);
        ReservationRepository reservations = mock(ReservationRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ReservationMonitoringService service = new ReservationMonitoringService(
                creation,
                outboxes,
                reservations,
                metrics,
                transactions,
                ReservationPropertiesFixture.runtimeDefaults());
        verifyNoInteractions(creation, outboxes, reservations);
        metrics.getMeters().forEach(meter -> meter.measure().iterator().forEachRemaining(ignored -> {}));
        verifyNoInteractions(creation, outboxes, reservations);
        assertThat(metrics.get("reservation.cancellation.outbox.pending")
                        .gauge()
                        .value())
                .isNaN();
        assertThat(metrics.get("reservation.cancellation.expiration.overdue")
                        .gauge()
                        .value())
                .isNaN();
        assertThat(metrics.get("reservation.cancellation.outbox.cleanup.backlog")
                        .gauge()
                        .value())
                .isNaN();
        assertThat(metrics.find("reservation.worker.enabled").meters()).isEmpty();
        ReservationBacklog snapshot = mock(ReservationBacklog.class);
        when(snapshot.getBacklog()).thenReturn(3.0);
        when(snapshot.getOldestAge()).thenReturn(20.0);
        when(snapshot.getMeasuredAt()).thenReturn(1000.0);
        when(outboxes.monitoringSnapshot()).thenReturn(snapshot);
        when(reservations.monitoringSnapshot()).thenReturn(snapshot);
        when(creation.monitoringSnapshot()).thenReturn(snapshot);
        when(outboxes.cleanupMonitoringSnapshot()).thenReturn(snapshot);
        service.refreshOutbox();
        service.refreshExpiration();
        service.refreshCreationCleanup();
        service.refreshOutboxCleanup();
        assertThat(metrics.get("reservation.cancellation.outbox.pending")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(metrics.get("reservation.cancellation.outbox.oldest.age")
                        .gauge()
                        .value())
                .isEqualTo(20);
        assertThat(metrics.get("reservation.creation.cleanup.expired.backlog")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(metrics.get("reservation.cancellation.outbox.cleanup.backlog")
                        .gauge()
                        .value())
                .isEqualTo(3);
        when(outboxes.monitoringSnapshot()).thenThrow(new IllegalStateException("database unavailable"));
        service.refreshOutbox();
        assertThat(metrics.get("reservation.cancellation.outbox.pending")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(metrics.get("reservation.observability.backlog.last.success.timestamp")
                        .tag("backlog", "outbox")
                        .gauge()
                        .value())
                .isEqualTo(1000);
        assertThat(metrics.get("reservation.observability.backlog.refresh.failures")
                        .tag("backlog", "outbox")
                        .counter()
                        .count())
                .isOne();
        when(snapshot.getBacklog()).thenReturn(0.0);
        when(snapshot.getOldestAge()).thenReturn(0.0);
        service.refreshExpiration();
        assertThat(metrics.get("reservation.cancellation.expiration.overdue")
                        .gauge()
                        .value())
                .isZero();
        assertThat(metrics.get("reservation.cancellation.expiration.oldest.age")
                        .gauge()
                        .value())
                .isZero();
        metrics.close();
    }
}
