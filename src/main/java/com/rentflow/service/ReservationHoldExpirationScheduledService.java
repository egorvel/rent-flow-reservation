package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnProperty(
        name = "reservation.cancellation.expiration.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationHoldExpirationScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationHoldExpirationScheduledService.class);

    private final ReservationCancellationService cancellation;
    private final ReservationRepository reservations;
    private final MeterRegistry metrics;
    private final int maxReservationsPerRun;
    private final long budgetNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    @Autowired
    public ReservationHoldExpirationScheduledService(
            ReservationCancellationService cancellation,
            ReservationRepository reservations,
            MeterRegistry metrics,
            @Value("${reservation.cancellation.expiration.max-reservations-per-run:100}") int maxReservationsPerRun,
            @Value("${reservation.cancellation.expiration.runtime-budget:5s}") Duration runtimeBudget) {
        this(cancellation, reservations, metrics, maxReservationsPerRun, runtimeBudget, System::nanoTime);
    }

    ReservationHoldExpirationScheduledService(
            ReservationCancellationService cancellation,
            ReservationRepository reservations,
            MeterRegistry metrics,
            int maxReservationsPerRun,
            Duration runtimeBudget,
            LongSupplier nanoTime) {
        if (maxReservationsPerRun <= 0) {
            throw new IllegalArgumentException("Expiration run limit must be positive");
        }
        if (runtimeBudget.isNegative() || runtimeBudget.isZero()) {
            throw new IllegalArgumentException("Expiration runtime budget must be positive");
        }
        this.cancellation = cancellation;
        this.reservations = reservations;
        this.metrics = metrics;
        this.maxReservationsPerRun = maxReservationsPerRun;
        this.budgetNanos = runtimeBudget.toNanos();
        this.nanoTime = nanoTime;
        metrics.gauge("reservation.cancellation.expiration.overdue", overdue);
        metrics.gauge("reservation.cancellation.expiration.oldest.age", oldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${reservation.cancellation.expiration.fixed-delay:5s}")
    public void expire() {
        long started = nanoTime.getAsLong();
        int expired = 0;
        try {
            while (expired < maxReservationsPerRun && nanoTime.getAsLong() - started < budgetNanos) {
                ReservationCancellationService.ExpirationResult result = cancellation.expireOldestHeld();
                if (result != ReservationCancellationService.ExpirationResult.EXPIRED) {
                    break;
                }
                metrics.counter("reservation.cancellation.expiration.transitions")
                        .increment();
                expired++;
            }
        } catch (RuntimeException exception) {
            metrics.counter("reservation.cancellation.expiration.failures").increment();
            LOGGER.warn("Reservation hold expiration failed; the next scheduled run will retry.");
        } finally {
            refreshBacklog();
        }
    }

    private void refreshBacklog() {
        try {
            Instant databaseTime = reservations.databaseTime();
            overdue.set(reservations.countByStatusAndHoldExpiresAtLessThanEqual(ReservationStatus.HELD, databaseTime));
            long age = reservations
                    .oldestOverdueHoldExpiresAt(ReservationStatus.HELD, databaseTime)
                    .map(oldest ->
                            Math.max(0L, Duration.between(oldest, databaseTime).toSeconds()))
                    .orElse(0L);
            oldestAgeSeconds.set(age);
        } catch (RuntimeException exception) {
            LOGGER.warn("Reservation hold expiration backlog metrics could not be refreshed.");
        }
    }
}
