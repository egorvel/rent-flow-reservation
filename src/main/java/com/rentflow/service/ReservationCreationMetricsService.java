package com.rentflow.service;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationCreationMetricsService {
    private final MeterRegistry meterRegistry;

    public ReservationCreationMetricsService(MeterRegistry meterRegistry, ReservationCreationStoreService store) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge(
                "reservation.creation.pending.due", store, ReservationCreationStoreService::countDuePending);
        meterRegistry.gauge(
                "reservation.creation.completed.expired",
                store,
                ReservationCreationStoreService::countExpiredCompleted);
        meterRegistry.gauge(
                "reservation.creation.reconciliation.backlog",
                store,
                ReservationCreationStoreService::countReconciliation);
    }

    public void request(String outcome) {
        increment("reservation.creation.requests", outcome);
    }

    public void recovery(String outcome) {
        increment("reservation.creation.recovery", outcome);
    }

    public void cleanup(String outcome) {
        increment("reservation.creation.cleanup", outcome);
    }

    private void increment(String name, String outcome) {
        meterRegistry.counter(name, "outcome", outcome).increment();
    }
}
