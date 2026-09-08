package com.rentflow.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.rentflow.model.ReservationCreationPreparation;

@Service
public class ReservationCreationRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCreationRecoveryService.class);

    private final ReservationCreationStoreService store;
    private final ReservationCreationService creationService;
    private final ReservationCreationMetricsService metrics;

    public ReservationCreationRecoveryService(
            ReservationCreationStoreService store,
            ReservationCreationService creationService,
            ReservationCreationMetricsService metrics) {
        this.store = store;
        this.creationService = creationService;
        this.metrics = metrics;
    }

    public void recoverDue() {
        List<UUID> keys = store.findDueKeys();
        for (UUID key : keys) {
            try {
                UUID owner = UUID.randomUUID();
                Optional<ReservationCreationPreparation> claimed = store.claimForRecovery(key, owner);
                if (claimed.isPresent()) {
                    creationService.execute(claimed.orElseThrow());
                    metrics.recovery("processed");
                }
            } catch (RuntimeException exception) {
                metrics.recovery("failure");
                LOGGER.warn(
                        "Reservation creation recovery failed ({})",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
