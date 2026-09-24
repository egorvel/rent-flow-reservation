package com.rentflow.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
    private final AtomicLong creationBacklog = new AtomicLong();
    private final AtomicLong cancellationBacklog = new AtomicLong();

    @Autowired
    public ReservationCleanupScheduledService(
            ReservationCleanupService cleanup, MeterRegistry metrics, ReservationRuntimeSettings settings) {
        this(
                cleanup,
                metrics,
                settings.creation().cleanup().runtimeBudget(),
                settings.cancellation().cleanup().runtimeBudget(),
                settings.cancellation().cleanup().enabled(),
                System::nanoTime);
    }

    ReservationCleanupScheduledService(
            ReservationCleanupService cleanup,
            MeterRegistry metrics,
            Duration creationBudget,
            Duration cancellationBudget,
            boolean cancellationEnabled,
            LongSupplier nanoTime) {
        requirePositive(creationBudget, "Creation cleanup runtime budget");
        requirePositive(cancellationBudget, "Cancellation cleanup runtime budget");
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.creationBudgetNanos = creationBudget.toNanos();
        this.cancellationBudgetNanos = cancellationBudget.toNanos();
        this.cancellationEnabled = cancellationEnabled;
        this.nanoTime = nanoTime;
        metrics.gauge("reservation.creation.cleanup.expired.backlog", creationBacklog);
        if (cancellationEnabled) {
            metrics.gauge("reservation.cancellation.outbox.cleanup.backlog", cancellationBacklog);
        }
    }

    @Scheduled(cron = "${reservation.creation.cleanup.cron}", zone = "UTC")
    public void cleanCreationRequests() {
        clean(
                cleanup::deleteExpiredCreationChunk,
                cleanup::countExpiredCreationRequests,
                creationBudgetNanos,
                creationBacklog,
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
                cleanup::countCancellationCleanupBacklog,
                cancellationBudgetNanos,
                cancellationBacklog,
                "reservation.cancellation.outbox.cleanup",
                "Reservation cancellation outbox cleanup failed; the next scheduled run will retry.");
    }

    private void clean(
            IntSupplier deleteChunk,
            LongSupplier countBacklog,
            long budgetNanos,
            AtomicLong backlog,
            String metricPrefix,
            String failureMessage) {
        long started = nanoTime.getAsLong();
        Timer.Sample sample = Timer.start(metrics);
        try {
            int deleted;
            do {
                deleted = deleteChunk.getAsInt();
                metrics.counter(metricPrefix + ".deleted").increment(deleted);
            } while (deleted == CHUNK_SIZE && nanoTime.getAsLong() - started < budgetNanos);
            backlog.set(countBacklog.getAsLong());
        } catch (RuntimeException exception) {
            metrics.counter(metricPrefix + ".failures").increment();
            LOGGER.warn(failureMessage);
        } finally {
            sample.stop(metrics.timer(metricPrefix + ".duration"));
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
