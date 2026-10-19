package com.rentflow.service;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rentflow.properties.ReservationProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
@ConditionalOnProperty(name = "reservation.cancellation.relay.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationCancellationOutboxRelayScheduledService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ReservationCancellationOutboxRelayScheduledService.class);

    private final ReservationCancellationOutboxRelayService relay;
    private final MeterRegistry metrics;
    private final ObservationRegistry observations;
    private final Clock clock;
    private final int maxEventsPerRun;
    private final long budgetNanos;
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();

    public ReservationCancellationOutboxRelayScheduledService(
            ReservationCancellationOutboxRelayService relay,
            MeterRegistry metrics,
            ReservationProperties properties,
            ObservationRegistry observations,
            Clock clock) {
        ReservationProperties.Cancellation.Relay relaySettings =
                properties.cancellation().relay();
        this.relay = relay;
        this.metrics = metrics;
        this.observations = observations;
        this.clock = clock;
        this.maxEventsPerRun = relaySettings.maxEventsPerRun();
        this.budgetNanos = relaySettings.runtimeBudget().toNanos();
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.run.timestamp", lastRun, AtomicLong::doubleValue)
                .tag("worker", "relay")
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "reservation.worker.last.success.timestamp", lastSuccess, AtomicLong::doubleValue)
                .tag("worker", "relay")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${reservation.cancellation.relay.fixed-delay}")
    public void publish() {
        long started = System.nanoTime();
        int published = 0;
        boolean restoreInterrupt = false;
        boolean failed = false;
        Observation observation = startWorkerObservation();
        try (Observation.Scope _ = observation.openScope()) {
            while (published < maxEventsPerRun && System.nanoTime() - started < budgetNanos) {
                ReservationCancellationOutboxRelayService.Result result = relay.publishOldest();
                if (result.status() == ReservationCancellationOutboxRelayService.Status.PUBLISHED) {
                    metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "published")
                            .increment();
                    published++;
                    continue;
                }
                if (result.status() == ReservationCancellationOutboxRelayService.Status.FAILED
                        || result.status() == ReservationCancellationOutboxRelayService.Status.INTERRUPTED) {
                    failed = true;
                    recordPublicationFailure(result, observation);
                    if (result.status() == ReservationCancellationOutboxRelayService.Status.INTERRUPTED) {
                        restoreInterrupt = true;
                    }
                }
                break;
            }
        } catch (RuntimeException exception) {
            failed = true;
            observation.error(exception);
            metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "failed")
                    .increment();
            LOGGER.warn("Reservation cancellation relay failed; the next scheduled run will retry.", exception);
        } catch (Error failure) {
            failed = true;
            observation.error(failure);
            throw failure;
        } finally {
            finishWorkerObservation(observation, failed);
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Observation startWorkerObservation() {
        lastRun.set(clock.instant().getEpochSecond());
        return Observation.createNotStarted("reservation.worker.run", observations)
                .lowCardinalityKeyValue("worker", "relay")
                .start();
    }

    private void recordPublicationFailure(
            ReservationCancellationOutboxRelayService.Result result, Observation observation) {
        metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "failed")
                .increment();
        metrics.counter("reservation.cancellation.outbox.retries.scheduled").increment();
        Throwable failure = result.failure() != null
                ? result.failure()
                : new IllegalStateException("Cancellation publication failed: " + result.failureCode());
        observation.error(failure);
        LOGGER.atWarn()
                .addKeyValue("eventId", result.eventId())
                .addKeyValue("attemptCount", result.attemptCount())
                .addKeyValue("failureCode", result.failureCode())
                .addKeyValue("nextAttemptAt", result.nextAttemptAt())
                .setCause(failure)
                .log("Reservation cancellation publication failed.");
    }

    private void finishWorkerObservation(Observation observation, boolean failed) {
        observation
                .lowCardinalityKeyValue("outcome", failed ? "error" : "success")
                .stop();
        if (!failed) {
            lastSuccess.set(clock.instant().getEpochSecond());
        }
    }
}
