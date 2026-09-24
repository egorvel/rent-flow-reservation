package com.rentflow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rentflow.service.ReservationRuntimeSettings;

@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationServiceConfig {

    @Bean
    ReservationRuntimeSettings reservationRuntimeSettings(ReservationProperties properties) {
        ReservationProperties.Creation.Cleanup creationCleanup =
                properties.creation().cleanup();
        ReservationProperties.Cancellation cancellation = properties.cancellation();
        ReservationProperties.Cancellation.Relay relay = cancellation.relay();
        ReservationProperties.Cancellation.Retry retry = cancellation.retry();
        ReservationProperties.Cancellation.Cleanup cleanup = cancellation.cleanup();
        ReservationProperties.Cancellation.Expiration expiration = cancellation.expiration();

        return new ReservationRuntimeSettings(
                new ReservationRuntimeSettings.Creation(
                        new ReservationRuntimeSettings.Creation.Cleanup(creationCleanup.runtimeBudget())),
                new ReservationRuntimeSettings.Cancellation(
                        cancellation.topic(),
                        cancellation.maxPayloadBytes(),
                        new ReservationRuntimeSettings.Cancellation.Relay(
                                relay.runtimeBudget(), relay.maxEventsPerRun(), relay.sendTimeout()),
                        new ReservationRuntimeSettings.Cancellation.Retry(
                                retry.initialBackoff(), retry.multiplier(), retry.maxBackoff(), retry.jitter()),
                        new ReservationRuntimeSettings.Cancellation.Cleanup(cleanup.enabled(), cleanup.runtimeBudget()),
                        new ReservationRuntimeSettings.Cancellation.Expiration(
                                expiration.holdDuration(),
                                expiration.runtimeBudget(),
                                expiration.maxReservationsPerRun())));
    }
}
