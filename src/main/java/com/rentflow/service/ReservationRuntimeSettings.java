package com.rentflow.service;

import java.time.Duration;

/** Immutable runtime settings exposed to the service layer by the configuration boundary. */
public record ReservationRuntimeSettings(Creation creation, Cancellation cancellation) {

    public record Creation(Cleanup cleanup) {
        public record Cleanup(Duration runtimeBudget) {}
    }

    public record Cancellation(
            String topic, int maxPayloadBytes, Relay relay, Retry retry, Cleanup cleanup, Expiration expiration) {

        public record Relay(Duration runtimeBudget, int maxEventsPerRun, Duration sendTimeout) {}

        public record Retry(Duration initialBackoff, double multiplier, Duration maxBackoff, double jitter) {}

        public record Cleanup(boolean enabled, Duration runtimeBudget) {}

        public record Expiration(Duration holdDuration, Duration runtimeBudget, int maxReservationsPerRun) {}
    }
}
