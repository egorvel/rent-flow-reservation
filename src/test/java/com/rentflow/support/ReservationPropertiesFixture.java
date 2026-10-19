package com.rentflow.support;

import java.time.Duration;

import com.rentflow.properties.ReservationProperties;

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
                                true, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 100)),
                new ReservationProperties.Observability(
                        Duration.ofSeconds(15), Duration.ofMinutes(1), Duration.ofSeconds(5)));
    }
}
