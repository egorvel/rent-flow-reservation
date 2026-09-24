package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rentflow.repository.ReservationCancellationOutboxRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnProperty(name = "reservation.cancellation.relay.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationCancellationOutboxRelayScheduledService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ReservationCancellationOutboxRelayScheduledService.class);

    private final ReservationCancellationOutboxRelayService relay;
    private final ReservationCancellationOutboxRepository outboxes;
    private final MeterRegistry metrics;
    private final int maxEventsPerRun;
    private final long budgetNanos;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public ReservationCancellationOutboxRelayScheduledService(
            ReservationCancellationOutboxRelayService relay,
            ReservationCancellationOutboxRepository outboxes,
            MeterRegistry metrics,
            ReservationRuntimeSettings settings) {
        ReservationRuntimeSettings.Cancellation.Relay relaySettings =
                settings.cancellation().relay();
        this.relay = relay;
        this.outboxes = outboxes;
        this.metrics = metrics;
        this.maxEventsPerRun = relaySettings.maxEventsPerRun();
        this.budgetNanos = relaySettings.runtimeBudget().toNanos();
        metrics.gauge("reservation.cancellation.outbox.pending", pending);
        metrics.gauge("reservation.cancellation.outbox.oldest.age", oldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${reservation.cancellation.relay.fixed-delay}")
    public void publish() {
        long started = System.nanoTime();
        int published = 0;
        boolean restoreInterrupt = false;
        try {
            while (published < maxEventsPerRun && System.nanoTime() - started < budgetNanos) {
                ReservationCancellationOutboxRelayService.Result result = relay.publishOldest();
                if (result.status() == ReservationCancellationOutboxRelayService.Status.PUBLISHED) {
                    metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "published")
                            .increment();
                    published++;
                    if (result.recovered()) {
                        LOGGER.info(
                                "Reservation cancellation publication recovered: eventId={}, attemptCount={}",
                                result.eventId(),
                                result.attemptCount());
                    }
                    continue;
                }
                if (result.status() == ReservationCancellationOutboxRelayService.Status.FAILED
                        || result.status() == ReservationCancellationOutboxRelayService.Status.INTERRUPTED) {
                    metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "failed")
                            .increment();
                    metrics.counter("reservation.cancellation.outbox.retries.scheduled")
                            .increment();
                    LOGGER.warn(
                            "Reservation cancellation publication failed: eventId={}, attemptCount={}, failureCode={}, nextAttemptAt={}",
                            result.eventId(),
                            result.attemptCount(),
                            result.failureCode(),
                            result.nextAttemptAt());
                    if (result.status() == ReservationCancellationOutboxRelayService.Status.INTERRUPTED) {
                        restoreInterrupt = true;
                    }
                }
                break;
            }
        } catch (RuntimeException exception) {
            metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "failed")
                    .increment();
            LOGGER.warn("Reservation cancellation relay failed; the next scheduled run will retry.");
        } finally {
            refreshBacklog();
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void refreshBacklog() {
        try {
            pending.set(outboxes.countByPublishedAtIsNull());
            Instant databaseTime = outboxes.databaseTime();
            long age = outboxes.oldestPendingOccurredAt()
                    .map(oldest ->
                            Math.max(0L, Duration.between(oldest, databaseTime).toSeconds()))
                    .orElse(0L);
            oldestAgeSeconds.set(age);
        } catch (RuntimeException exception) {
            LOGGER.warn("Reservation cancellation backlog metrics could not be refreshed.");
        }
    }
}
