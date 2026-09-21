package com.rentflow.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
@ConditionalOnProperty(name = "reservation.cancellation.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationCancellationOutboxCleanupScheduledService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ReservationCancellationOutboxCleanupScheduledService.class);
    private static final int CHUNK_SIZE = 1000;

    private final ReservationCancellationOutboxCleanupService cleanup;
    private final MeterRegistry metrics;
    private final long budgetNanos;
    private final AtomicLong backlog = new AtomicLong();

    public ReservationCancellationOutboxCleanupScheduledService(
            ReservationCancellationOutboxCleanupService cleanup,
            MeterRegistry metrics,
            @Value("${reservation.cancellation.cleanup.runtime-budget:60s}") Duration runtimeBudget) {
        this.cleanup = cleanup;
        this.metrics = metrics;
        this.budgetNanos = runtimeBudget.toNanos();
        metrics.gauge("reservation.cancellation.outbox.cleanup.backlog", backlog);
    }

    @Scheduled(cron = "${reservation.cancellation.cleanup.cron:0 30 3 * * *}", zone = "UTC")
    public void clean() {
        long started = System.nanoTime();
        Timer.Sample sample = Timer.start(metrics);
        try {
            int deleted;
            do {
                deleted = cleanup.deleteChunk();
                metrics.counter("reservation.cancellation.outbox.cleanup.deleted")
                        .increment(deleted);
            } while (deleted == CHUNK_SIZE && System.nanoTime() - started < budgetNanos);
            backlog.set(cleanup.countBacklog());
        } catch (RuntimeException exception) {
            metrics.counter("reservation.cancellation.outbox.cleanup.failures").increment();
            LOGGER.warn("Reservation cancellation outbox cleanup failed; the next scheduled run will retry.");
        } finally {
            sample.stop(metrics.timer("reservation.cancellation.outbox.cleanup.duration"));
        }
    }
}
