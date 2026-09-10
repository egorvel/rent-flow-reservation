package com.rentflow.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ReservationCreationCleanupScheduledService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCreationCleanupScheduledService.class);
    private static final int CHUNK_SIZE = 1000;

    private final ReservationCreationCleanupService cleanup;
    private final MeterRegistry metrics;
    private final long budgetNanos;
    private final AtomicLong backlog = new AtomicLong();

    public ReservationCreationCleanupScheduledService(
            ReservationCreationCleanupService cleanup,
            MeterRegistry metrics,
            @Value("${reservation.creation.cleanup.runtime-budget:60s}") Duration budget) {
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Cleanup runtime budget must be positive");
        }
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.budgetNanos = budget.toNanos();
        metrics.gauge("reservation.creation.cleanup.expired.backlog", backlog);
    }

    @Scheduled(cron = "${reservation.creation.cleanup.cron:0 0 3 * * *}", zone = "UTC")
    public void clean() {
        long started = System.nanoTime();
        Timer.Sample sample = Timer.start(metrics);
        try {
            int deleted;
            do {
                deleted = cleanup.deleteChunk();
                metrics.counter("reservation.creation.cleanup.deleted").increment(deleted);
            } while (deleted == CHUNK_SIZE && System.nanoTime() - started < budgetNanos);
            backlog.set(cleanup.countExpired());
        } catch (RuntimeException exception) {
            metrics.counter("reservation.creation.cleanup.failures").increment();
            LOGGER.warn("Reservation creation cleanup failed; the next scheduled run will retry.");
        } finally {
            sample.stop(metrics.timer("reservation.creation.cleanup.duration"));
        }
    }
}
