package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("reservation.cancellation")
public record ReservationCancellationProperties(
        @NotBlank String topic,
        @Positive int maxPayloadBytes,
        @Valid @NotNull Relay relay,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Cleanup cleanup,
        @Valid @NotNull Expiration expiration) {

    public record Relay(
            @NotNull Duration fixedDelay,
            @NotNull Duration runtimeBudget,
            @Positive int maxEventsPerRun,
            @NotNull Duration sendTimeout) {
        public Relay {
            requirePositive(fixedDelay, "relay.fixedDelay");
            requirePositive(runtimeBudget, "relay.runtimeBudget");
            requirePositive(sendTimeout, "relay.sendTimeout");
        }
    }

    public record Retry(
            @NotNull Duration initialBackoff,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxBackoff,

            @DecimalMin("0.0") @DecimalMax(value = "1.0", inclusive = false) double jitter) {
        public Retry {
            requirePositive(initialBackoff, "retry.initialBackoff");
            requirePositive(maxBackoff, "retry.maxBackoff");
            if (initialBackoff != null && maxBackoff != null && maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("retry.maxBackoff must not be less than retry.initialBackoff");
            }
        }
    }

    public record Cleanup(@NotBlank String cron, @NotNull Duration runtimeBudget) {
        public Cleanup {
            requirePositive(runtimeBudget, "cleanup.runtimeBudget");
        }
    }

    public record Expiration(
            boolean enabled,
            @NotNull Duration holdDuration,
            @NotNull Duration fixedDelay,
            @NotNull Duration runtimeBudget,
            @Positive int maxReservationsPerRun) {
        public Expiration {
            requirePositive(holdDuration, "expiration.holdDuration");
            requirePositive(fixedDelay, "expiration.fixedDelay");
            requirePositive(runtimeBudget, "expiration.runtimeBudget");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
