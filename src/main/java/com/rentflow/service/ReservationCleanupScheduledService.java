package com.rentflow.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rentflow.properties.ReservationProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
public class ReservationCleanupScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCleanupScheduledService.class);
    private static final int CHUNK_SIZE = 1000;

    private final ReservationCleanupService cleanup;
    private final MeterRegistry metrics;
    private final long creationBudgetNanos;
    private final long cancellationBudgetNanos;
    private final boolean cancellationEnabled;
    private final LongSupplier nanoTime;
    private final AtomicLong creationLastRun = new AtomicLong();
    private final AtomicLong creationLastSuccess = new AtomicLong();
    private final AtomicLong cancellationLastRun = new AtomicLong();
    private final AtomicLong cancellationLastSuccess = new AtomicLong();
    private final ObservationRegistry observations;
    private final Clock clock;

    @Autowired
    public ReservationCleanupScheduledService(
            ReservationCleanupService cleanup,
            MeterRegistry metrics,
            ReservationProperties properties,
            ObservationRegistry observations,
            Clock clock) {
        this(
                cleanup,
                metrics,
                properties.creation().cleanup().runtimeBudget(),
                properties.cancellation().cleanup().runtimeBudget(),
                properties.cancellation().cleanup().enabled(),
                System::nanoTime,
                observations,
                clock);
    }

    ReservationCleanupScheduledService(
            ReservationCleanupService cleanup,
            MeterRegistry metrics,
            Duration creationBudget,
            Duration cancellationBudget,
            boolean cancellationEnabled,
            LongSupplier nanoTime,
            ObservationRegistry observations,
            Clock clock) {
        requirePositive(creationBudget, "Creation cleanup runtime budget");
        requirePositive(cancellationBudget, "Cancellation cleanup runtime budget");
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.creationBudgetNanos = creationBudget.toNanos();
        this.cancellationBudgetNanos = cancellationBudget.toNanos();
        this.cancellationEnabled = cancellationEnabled;
        this.nanoTime = nanoTime;
        this.observations = observations;
        this.clock = clock;
        registerTimestamps("creation_cleanup", creationLastRun, creationLastSuccess);
        registerTimestamps("outbox_cleanup", cancellationLastRun, cancellationLastSuccess);
    }

    private void registerTimestamps(String worker, AtomicLong run, AtomicLong success) {
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.run.timestamp", run, AtomicLong::doubleValue)
                .tag("worker", worker)
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.success.timestamp", success, AtomicLong::doubleValue)
                .tag("worker", worker)
                .register(metrics);
    }

    @Scheduled(cron = "${reservation.creation.cleanup.cron}", zone = "UTC")
    public void cleanCreationRequests() {
        clean(
                cleanup::deleteExpiredCreationChunk,
                creationBudgetNanos,
                creationLastRun,
                creationLastSuccess,
                "creation_cleanup",
                "reservation.creation.cleanup",
                "Reservation creation cleanup failed; the next scheduled run will retry.");
    }

    @Scheduled(cron = "${reservation.cancellation.cleanup.cron}", zone = "UTC")
    public void cleanCancellationOutbox() {
        if (!cancellationEnabled) {
            return;
        }
        clean(
                cleanup::deletePublishedCancellationChunk,
                cancellationBudgetNanos,
                cancellationLastRun,
                cancellationLastSuccess,
                "outbox_cleanup",
                "reservation.cancellation.outbox.cleanup",
                "Reservation cancellation outbox cleanup failed; the next scheduled run will retry.");
    }

    private void clean(
            IntSupplier deleteChunk,
            long budgetNanos,
            AtomicLong lastRun,
            AtomicLong lastSuccess,
            String worker,
            String metricPrefix,
            String failureMessage) {
        long started = nanoTime.getAsLong();
        Timer.Sample sample = Timer.start(metrics);
        boolean failed = false;
        lastRun.set(clock.instant().getEpochSecond());
        Observation observation = Observation.createNotStarted("reservation.worker.run", observations)
                .lowCardinalityKeyValue("worker", worker)
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            int deleted;
            do {
                deleted = deleteChunk.getAsInt();
                metrics.counter(metricPrefix + ".deleted").increment(deleted);
            } while (deleted == CHUNK_SIZE && nanoTime.getAsLong() - started < budgetNanos);
        } catch (RuntimeException exception) {
            failed = true;
            observation.error(exception);
            metrics.counter(metricPrefix + ".failures").increment();
            LOGGER.warn(failureMessage, exception);
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
            sample.stop(metrics.timer(metricPrefix + ".duration"));
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
