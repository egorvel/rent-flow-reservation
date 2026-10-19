package com.rentflow.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
@ConditionalOnProperty(
        name = "reservation.cancellation.expiration.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationHoldExpirationScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationHoldExpirationScheduledService.class);

    private final ReservationCancellationService cancellation;
    private final MeterRegistry metrics;
    private final ObservationRegistry observations;
    private final Clock clock;
    private final int maxReservationsPerRun;
    private final long budgetNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();

    @Autowired
    public ReservationHoldExpirationScheduledService(
            ReservationCancellationService cancellation,
            MeterRegistry metrics,
            ReservationRuntimeSettings settings,
            ObservationRegistry observations,
            Clock clock) {
        this(
                cancellation,
                metrics,
                settings.cancellation().expiration().maxReservationsPerRun(),
                settings.cancellation().expiration().runtimeBudget(),
                System::nanoTime,
                observations,
                clock);
    }

    ReservationHoldExpirationScheduledService(
            ReservationCancellationService cancellation,
            MeterRegistry metrics,
            int maxReservationsPerRun,
            Duration runtimeBudget,
            LongSupplier nanoTime,
            ObservationRegistry observations,
            Clock clock) {
        if (maxReservationsPerRun <= 0) {
            throw new IllegalArgumentException("Expiration run limit must be positive");
        }
        if (runtimeBudget.isNegative() || runtimeBudget.isZero()) {
            throw new IllegalArgumentException("Expiration runtime budget must be positive");
        }
        this.cancellation = cancellation;
        this.metrics = metrics;
        this.observations = observations;
        this.clock = clock;
        this.maxReservationsPerRun = maxReservationsPerRun;
        this.budgetNanos = runtimeBudget.toNanos();
        this.nanoTime = nanoTime;
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.run.timestamp", lastRun, AtomicLong::doubleValue)
                .tag("worker", "expiration")
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.success.timestamp", lastSuccess, AtomicLong::doubleValue)
                .tag("worker", "expiration")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${reservation.cancellation.expiration.fixed-delay}")
    public void expire() {
        long started = nanoTime.getAsLong();
        int expired = 0;
        boolean failed = false;
        lastRun.set(clock.instant().getEpochSecond());
        Observation observation = Observation.createNotStarted("reservation.worker.run", observations)
                .lowCardinalityKeyValue("worker", "expiration")
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
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
            failed = true;
            observation.error(exception);
            metrics.counter("reservation.cancellation.expiration.failures").increment();
            LOGGER.warn("Reservation hold expiration failed; the next scheduled run will retry.", exception);
        } catch (Error failure) {
            failed = true;
            observation.error(failure);
            throw failure;
        } finally {
            observation
                    .lowCardinalityKeyValue("outcome", failed ? "error" : "success")
                    .stop();
            if (!failed) {
                lastSuccess.set(clock.instant().getEpochSecond());
            }
        }
    }
}
