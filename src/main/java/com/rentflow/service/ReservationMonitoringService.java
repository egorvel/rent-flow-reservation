package com.rentflow.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentflow.model.ReservationBacklog;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationMonitoringService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationMonitoringService.class);
    private final ReservationCreationRequestRepository creationRequests;
    private final ReservationCancellationOutboxRepository outboxes;
    private final ReservationRepository reservations;
    private final MeterRegistry metrics;
    private final Duration queryTimeout;
    private final TransactionTemplate transaction;
    private final AtomicReference<Snapshot> outbox = new AtomicReference<>(Snapshot.UNKNOWN);
    private final AtomicReference<Snapshot> expiration = new AtomicReference<>(Snapshot.UNKNOWN);
    private final AtomicReference<Snapshot> creationCleanup = new AtomicReference<>(Snapshot.UNKNOWN);
    private final AtomicReference<Snapshot> outboxCleanup = new AtomicReference<>(Snapshot.UNKNOWN);

    public ReservationMonitoringService(
            ReservationCreationRequestRepository creationRequests,
            ReservationCancellationOutboxRepository outboxes,
            ReservationRepository reservations,
            MeterRegistry metrics,
            PlatformTransactionManager transactions,
            ReservationRuntimeSettings settings) {
        this.creationRequests = creationRequests;
        this.outboxes = outboxes;
        this.reservations = reservations;
        this.metrics = metrics;
        this.queryTimeout = settings.observability().queryTimeout();
        transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        long timeoutSeconds = Math.ceilDiv(queryTimeout.toMillis(), 1000);
        transaction.setTimeout(Math.toIntExact(timeoutSeconds));
        register(
                "outbox",
                outbox,
                "reservation.cancellation.outbox.pending",
                "reservation.cancellation.outbox.oldest.age");
        register(
                "expiration",
                expiration,
                "reservation.cancellation.expiration.overdue",
                "reservation.cancellation.expiration.oldest.age");
        register("creation_cleanup", creationCleanup, "reservation.creation.cleanup.expired.backlog", null);
        register("outbox_cleanup", outboxCleanup, "reservation.cancellation.outbox.cleanup.backlog", null);
    }

    private void register(String family, AtomicReference<Snapshot> state, String countName, String ageName) {
        Gauge.builder(countName, state, value -> value.get().count()).register(metrics);
        if (ageName != null) {
            Gauge.builder(ageName, state, value -> value.get().age()).register(metrics);
        }
        Gauge.builder(
                        "reservation.observability.backlog.last.success.timestamp",
                        state,
                        value -> value.get().measuredAt())
                .tag("backlog", family)
                .register(metrics);
    }

    @Scheduled(
            fixedDelayString = "${reservation.observability.operational-backlog-fixed-delay}",
            scheduler = "reservationMonitoringTaskScheduler")
    public void refreshOutbox() {
        refresh("outbox", outbox, outboxes::monitoringSnapshot);
    }

    @Scheduled(
            fixedDelayString = "${reservation.observability.operational-backlog-fixed-delay}",
            scheduler = "reservationMonitoringTaskScheduler")
    public void refreshExpiration() {
        refresh("expiration", expiration, reservations::monitoringSnapshot);
    }

    @Scheduled(
            fixedDelayString = "${reservation.observability.cleanup-backlog-fixed-delay}",
            scheduler = "reservationMonitoringTaskScheduler")
    public void refreshCreationCleanup() {
        refresh("creation_cleanup", creationCleanup, creationRequests::monitoringSnapshot);
    }

    @Scheduled(
            fixedDelayString = "${reservation.observability.cleanup-backlog-fixed-delay}",
            scheduler = "reservationMonitoringTaskScheduler")
    public void refreshOutboxCleanup() {
        refresh("outbox_cleanup", outboxCleanup, outboxes::cleanupMonitoringSnapshot);
    }

    private void refresh(String family, AtomicReference<Snapshot> target, Supplier<ReservationBacklog> query) {
        try {
            Snapshot measured = transaction.execute(status -> {
                creationRequests.configureMonitoringTimeout(Long.toString(queryTimeout.toMillis()));
                ReservationBacklog result = query.get();
                return new Snapshot(result.getBacklog(), Math.max(0, result.getOldestAge()), result.getMeasuredAt());
            });
            target.set(java.util.Objects.requireNonNull(measured));
        } catch (RuntimeException failure) {
            metrics.counter("reservation.observability.backlog.refresh.failures", "backlog", family)
                    .increment();
            LOGGER.atWarn()
                    .addKeyValue("backlog", family)
                    .setCause(failure)
                    .log("Reservation backlog measurement failed.");
        }
    }

    private record Snapshot(double count, double age, double measuredAt) {
        private static final Snapshot UNKNOWN = new Snapshot(Double.NaN, Double.NaN, 0);
    }
}
