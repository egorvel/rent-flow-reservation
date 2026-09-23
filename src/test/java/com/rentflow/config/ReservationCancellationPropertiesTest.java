package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationCancellationPropertiesTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheProductionDefaults() {
        ReservationCancellationProperties properties = properties(Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsInvalidRetryBoundsAndJitter() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), 2, Duration.ofSeconds(1), 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(validator.validate(properties(Duration.ofSeconds(1), 0.5, Duration.ofMinutes(5), 1.0)))
                .hasSize(2);
    }

    @Test
    void rejectsNonPositiveExpirationDurations() {
        assertThatThrownBy(() -> new ReservationCancellationProperties.Expiration(
                        true, Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdDuration");
        assertThatThrownBy(() -> new ReservationCancellationProperties.Expiration(
                        true, Duration.ofMinutes(10), Duration.ZERO, Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixedDelay");
    }

    private ReservationCancellationProperties properties(
            Duration initial, double multiplier, Duration maximum, double jitter) {
        return new ReservationCancellationProperties(
                "rentflow.reservation.cancelled.v1",
                4096,
                new ReservationCancellationProperties.Relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(50)),
                new ReservationCancellationProperties.Retry(initial, multiplier, maximum, jitter),
                new ReservationCancellationProperties.Cleanup("0 30 3 * * *", Duration.ofSeconds(60)),
                new ReservationCancellationProperties.Expiration(
                        true, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 100));
    }
}
