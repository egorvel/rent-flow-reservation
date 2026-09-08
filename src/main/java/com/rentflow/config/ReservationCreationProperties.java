package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import com.rentflow.service.ReservationCreationSettings;

@Validated
@ConfigurationProperties("reservation.creation")
public record ReservationCreationProperties(
        @NotNull @DefaultValue("30s") Duration leaseDuration,
        @Valid @NotNull @DefaultValue Recovery recovery,
        @Valid @NotNull @DefaultValue Cleanup cleanup)
        implements ReservationCreationSettings {

    @Override
    public int recoveryBatchSize() {
        return recovery.batchSize();
    }

    @Override
    public Duration recoveryInitialBackoff() {
        return recovery.initialBackoff();
    }

    @Override
    public Duration recoveryMaxBackoff() {
        return recovery.maxBackoff();
    }

    @Override
    public double recoveryJitterFactor() {
        return recovery.jitterFactor();
    }

    @Override
    public Duration cleanupRuntimeBudget() {
        return cleanup.runtimeBudget();
    }

    @AssertTrue(message = "lease duration must be positive") public boolean isLeaseDurationValid() {
        return positive(leaseDuration);
    }

    public record Recovery(
            @NotNull @DefaultValue("30s") Duration fixedDelay,
            @Min(1) @Max(100) @DefaultValue("100") int batchSize,
            @NotNull @DefaultValue("30s") Duration initialBackoff,
            @NotNull @DefaultValue("30m") Duration maxBackoff,
            @DefaultValue("0.2") double jitterFactor) {

        @AssertTrue(message = "recovery durations and jitter are invalid") public boolean isValid() {
            return positive(fixedDelay)
                    && positive(initialBackoff)
                    && positive(maxBackoff)
                    && maxBackoff.compareTo(initialBackoff) >= 0
                    && jitterFactor >= 0
                    && jitterFactor < 1;
        }
    }

    public record Cleanup(
            @NotNull @DefaultValue("0 0 3 * * *") String cron,
            @NotNull @DefaultValue("60s") Duration runtimeBudget) {

        @AssertTrue(message = "cleanup cron and runtime budget are invalid") public boolean isValid() {
            if (!positive(runtimeBudget) || cron == null) {
                return false;
            }
            try {
                CronExpression.parse(cron);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
