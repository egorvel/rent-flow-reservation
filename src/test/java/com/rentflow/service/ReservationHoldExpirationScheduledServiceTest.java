package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationHoldExpirationScheduledServiceTest {
    private static final Instant DATABASE_TIME = Instant.parse("2026-09-22T12:00:00Z");

    private ReservationCancellationService cancellation;
    private ReservationRepository reservations;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        cancellation = mock(ReservationCancellationService.class);
        reservations = mock(ReservationRepository.class);
        metrics = new SimpleMeterRegistry();
        when(reservations.databaseTime()).thenReturn(DATABASE_TIME);
        when(reservations.oldestOverdueHoldExpiresAt(ReservationStatus.HELD, DATABASE_TIME))
                .thenReturn(Optional.empty());
    }

    @Test
    void drainsUntilTheConfiguredCountLimit() {
        when(cancellation.expireOldestHeld()).thenReturn(ReservationCancellationService.ExpirationResult.EXPIRED);

        scheduler(2, () -> 0L).expire();

        verify(cancellation, times(2)).expireOldestHeld();
        assertThat(metrics.counter("reservation.cancellation.expiration.transitions")
                        .count())
                .isEqualTo(2);
    }

    @Test
    void stopsOnEmptyOrBusyResults() {
        when(cancellation.expireOldestHeld())
                .thenReturn(ReservationCancellationService.ExpirationResult.EXPIRED)
                .thenReturn(ReservationCancellationService.ExpirationResult.EMPTY);

        scheduler(100, () -> 0L).expire();

        verify(cancellation, times(2)).expireOldestHeld();
    }

    @Test
    void stopsBeforeWorkWhenTheRuntimeBudgetIsConsumed() {
        LongSupplier nanoTime = org.mockito.Mockito.mock(LongSupplier.class);
        when(nanoTime.getAsLong()).thenReturn(0L, Duration.ofSeconds(5).toNanos());

        scheduler(100, nanoTime).expire();

        verify(cancellation, times(0)).expireOldestHeld();
    }

    @Test
    void recordsFailureAndRefreshesBacklog() {
        when(cancellation.expireOldestHeld()).thenThrow(new IllegalStateException("database unavailable"));
        when(reservations.countByStatusAndHoldExpiresAtLessThanEqual(ReservationStatus.HELD, DATABASE_TIME))
                .thenReturn(3L);
        when(reservations.oldestOverdueHoldExpiresAt(ReservationStatus.HELD, DATABASE_TIME))
                .thenReturn(Optional.of(DATABASE_TIME.minusSeconds(20)));

        scheduler(100, () -> 0L).expire();

        assertThat(metrics.counter("reservation.cancellation.expiration.failures")
                        .count())
                .isOne();
        assertThat(metrics.get("reservation.cancellation.expiration.overdue")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(metrics.get("reservation.cancellation.expiration.oldest.age")
                        .gauge()
                        .value())
                .isEqualTo(20);
    }

    private ReservationHoldExpirationScheduledService scheduler(int maxReservations, LongSupplier nanoTime) {
        return new ReservationHoldExpirationScheduledService(
                cancellation, reservations, metrics, maxReservations, Duration.ofSeconds(5), nanoTime);
    }
}
