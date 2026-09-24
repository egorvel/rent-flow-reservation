package com.rentflow.config;

import java.time.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import com.rentflow.service.ReservationRuntimeSettings;
import com.rentflow.support.ReservationPropertiesFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationPropertiesTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheProductionDefaults() {
        assertThat(validator.validate(ReservationPropertiesFixture.defaults())).isEmpty();
    }

    @Test
    void projectsValidatedPropertiesIntoServiceSettings() {
        ReservationRuntimeSettings settings =
                new ReservationServiceConfig().reservationRuntimeSettings(ReservationPropertiesFixture.defaults());

        assertThat(settings.creation().cleanup().runtimeBudget()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.cancellation().topic()).isEqualTo("rentflow.reservation.cancelled.v1");
        assertThat(settings.cancellation().maxPayloadBytes()).isEqualTo(4096);
        assertThat(settings.cancellation().relay())
                .isEqualTo(new ReservationRuntimeSettings.Cancellation.Relay(
                        Duration.ofSeconds(5), 100, Duration.ofSeconds(50)));
        assertThat(settings.cancellation().retry())
                .isEqualTo(new ReservationRuntimeSettings.Cancellation.Retry(
                        Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2));
        assertThat(settings.cancellation().cleanup())
                .isEqualTo(new ReservationRuntimeSettings.Cancellation.Cleanup(true, Duration.ofSeconds(60)));
        assertThat(settings.cancellation().expiration())
                .isEqualTo(new ReservationRuntimeSettings.Cancellation.Expiration(
                        Duration.ofMinutes(10), Duration.ofSeconds(5), 100));
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
        assertThatThrownBy(() -> new ReservationProperties.Cancellation.Expiration(
                        true, Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdDuration");
        assertThatThrownBy(() -> new ReservationProperties.Cancellation.Expiration(
                        true, Duration.ofMinutes(10), Duration.ZERO, Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixedDelay");
    }

    @Test
    void rejectsInvalidCleanupSettings() {
        assertThatThrownBy(() -> new ReservationProperties.Creation.Cleanup("0 0 3 * * *", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creation.cleanup.runtimeBudget");

        ReservationProperties properties = properties(Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2);
        ReservationProperties withBlankCron = new ReservationProperties(
                new ReservationProperties.Creation(
                        new ReservationProperties.Creation.Cleanup(" ", Duration.ofSeconds(60))),
                properties.cancellation());
        assertThat(validator.validate(withBlankCron)).hasSize(1);
    }

    private ReservationProperties properties(Duration initial, double multiplier, Duration maximum, double jitter) {
        return new ReservationProperties(
                new ReservationProperties.Creation(
                        new ReservationProperties.Creation.Cleanup("0 0 3 * * *", Duration.ofSeconds(60))),
                new ReservationProperties.Cancellation(
                        "rentflow.reservation.cancelled.v1",
                        4096,
                        new ReservationProperties.Cancellation.Relay(
                                true, Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(50)),
                        new ReservationProperties.Cancellation.Retry(initial, multiplier, maximum, jitter),
                        new ReservationProperties.Cancellation.Cleanup(true, "0 30 3 * * *", Duration.ofSeconds(60)),
                        new ReservationProperties.Cancellation.Expiration(
                                true, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 100)));
    }
}
