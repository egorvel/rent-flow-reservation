package com.rentflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReservationCreationCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCreationCleanupService.class);
    private static final int CHUNK_SIZE = 1000;

    private final ReservationCreationStoreService store;
    private final ReservationCreationSettings settings;
    private final ReservationCreationMetricsService metrics;

    public ReservationCreationCleanupService(
            ReservationCreationStoreService store,
            ReservationCreationSettings settings,
            ReservationCreationMetricsService metrics) {
        this.store = store;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${reservation.creation.cleanup.cron:0 0 3 * * *}", zone = "UTC")
    public void clean() {
        long deadline = System.nanoTime() + settings.cleanupRuntimeBudget().toNanos();
        try {
            int deleted;
            do {
                deleted = store.deleteExpiredCompleted(CHUNK_SIZE);
            } while (deleted == CHUNK_SIZE && System.nanoTime() < deadline);
            metrics.cleanup("completed");
        } catch (RuntimeException exception) {
            metrics.cleanup("failure");
            LOGGER.warn(
                    "Reservation creation cleanup failed ({})",
                    exception.getClass().getSimpleName());
        }
    }
}
