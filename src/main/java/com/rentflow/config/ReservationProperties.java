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
@ConfigurationProperties("reservation")
public record ReservationProperties(
        @Valid @NotNull Creation creation, @Valid @NotNull Cancellation cancellation) {

    public record Creation(@Valid @NotNull Cleanup cleanup) {
        public record Cleanup(
                @NotBlank String cron, @NotNull Duration runtimeBudget) {
            public Cleanup {
                requirePositive(runtimeBudget, "creation.cleanup.runtimeBudget");
            }
        }
    }

    public record Cancellation(
            @NotBlank String topic,
            @Positive int maxPayloadBytes,
            @Valid @NotNull Relay relay,
            @Valid @NotNull Retry retry,
            @Valid @NotNull Cleanup cleanup,
            @Valid @NotNull Expiration expiration) {

        public record Relay(
                boolean enabled,
                @NotNull Duration fixedDelay,
                @NotNull Duration runtimeBudget,
                @Positive int maxEventsPerRun,
                @NotNull Duration sendTimeout) {
            public Relay {
                requirePositive(fixedDelay, "cancellation.relay.fixedDelay");
                requirePositive(runtimeBudget, "cancellation.relay.runtimeBudget");
                requirePositive(sendTimeout, "cancellation.relay.sendTimeout");
            }
        }

        public record Retry(
                @NotNull Duration initialBackoff,
                @DecimalMin("1.0") double multiplier,
                @NotNull Duration maxBackoff,

                @DecimalMin("0.0") @DecimalMax(value = "1.0", inclusive = false) double jitter) {
            public Retry {
                requirePositive(initialBackoff, "cancellation.retry.initialBackoff");
                requirePositive(maxBackoff, "cancellation.retry.maxBackoff");
                if (initialBackoff != null && maxBackoff != null && maxBackoff.compareTo(initialBackoff) < 0) {
                    throw new IllegalArgumentException(
                            "cancellation.retry.maxBackoff must not be less than cancellation.retry.initialBackoff");
                }
            }
        }

        public record Cleanup(
                boolean enabled,
                @NotBlank String cron,
                @NotNull Duration runtimeBudget) {
            public Cleanup {
                requirePositive(runtimeBudget, "cancellation.cleanup.runtimeBudget");
            }
        }

        public record Expiration(
                boolean enabled,
                @NotNull Duration holdDuration,
                @NotNull Duration fixedDelay,
                @NotNull Duration runtimeBudget,
                @Positive int maxReservationsPerRun) {
            public Expiration {
                requirePositive(holdDuration, "cancellation.expiration.holdDuration");
                requirePositive(fixedDelay, "cancellation.expiration.fixedDelay");
                requirePositive(runtimeBudget, "cancellation.expiration.runtimeBudget");
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
