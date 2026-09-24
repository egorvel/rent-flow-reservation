package com.rentflow.support;

import java.time.Duration;

import com.rentflow.config.ReservationProperties;
import com.rentflow.service.ReservationRuntimeSettings;

public final class ReservationPropertiesFixture {
    private ReservationPropertiesFixture() {}

    public static ReservationProperties defaults() {
        return properties(4096, 100, Duration.ofSeconds(5));
    }

    public static ReservationProperties withMaxPayloadBytes(int maxPayloadBytes) {
        return properties(maxPayloadBytes, 100, Duration.ofSeconds(5));
    }

    public static ReservationProperties withRelayLimits(int maxEventsPerRun, Duration runtimeBudget) {
        return properties(4096, maxEventsPerRun, runtimeBudget);
    }

    public static ReservationRuntimeSettings runtimeDefaults() {
        return runtimeSettings(4096, 100, Duration.ofSeconds(5));
    }

    public static ReservationRuntimeSettings runtimeWithMaxPayloadBytes(int maxPayloadBytes) {
        return runtimeSettings(maxPayloadBytes, 100, Duration.ofSeconds(5));
    }

    public static ReservationRuntimeSettings runtimeWithRelayLimits(int maxEventsPerRun, Duration runtimeBudget) {
        return runtimeSettings(4096, maxEventsPerRun, runtimeBudget);
    }

    private static ReservationProperties properties(
            int maxPayloadBytes, int maxEventsPerRun, Duration relayRuntimeBudget) {
        return new ReservationProperties(
                new ReservationProperties.Creation(
                        new ReservationProperties.Creation.Cleanup("0 0 3 * * *", Duration.ofSeconds(60))),
                new ReservationProperties.Cancellation(
                        "rentflow.reservation.cancelled.v1",
                        maxPayloadBytes,
                        new ReservationProperties.Cancellation.Relay(
                                true,
                                Duration.ofSeconds(1),
                                relayRuntimeBudget,
                                maxEventsPerRun,
                                Duration.ofSeconds(50)),
                        new ReservationProperties.Cancellation.Retry(
                                Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2),
                        new ReservationProperties.Cancellation.Cleanup(true, "0 30 3 * * *", Duration.ofSeconds(60)),
                        new ReservationProperties.Cancellation.Expiration(
                                true, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 100)));
    }

    private static ReservationRuntimeSettings runtimeSettings(
            int maxPayloadBytes, int maxEventsPerRun, Duration relayRuntimeBudget) {
        return new ReservationRuntimeSettings(
                new ReservationRuntimeSettings.Creation(
                        new ReservationRuntimeSettings.Creation.Cleanup(Duration.ofSeconds(60))),
                new ReservationRuntimeSettings.Cancellation(
                        "rentflow.reservation.cancelled.v1",
                        maxPayloadBytes,
                        new ReservationRuntimeSettings.Cancellation.Relay(
                                relayRuntimeBudget, maxEventsPerRun, Duration.ofSeconds(50)),
                        new ReservationRuntimeSettings.Cancellation.Retry(
                                Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2),
                        new ReservationRuntimeSettings.Cancellation.Cleanup(true, Duration.ofSeconds(60)),
                        new ReservationRuntimeSettings.Cancellation.Expiration(
                                Duration.ofMinutes(10), Duration.ofSeconds(5), 100)));
    }
}
