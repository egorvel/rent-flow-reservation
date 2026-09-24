package com.rentflow.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationOutcome.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReservationCreationValidationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    @Test
    void reportsEveryLaterExactDuplicateAndReversedPeriod() {
        ReservationCreationCommand command = new ReservationCreationCommand(
                "customer",
                "order",
                List.of(
                        item("Serial", TODAY, TODAY),
                        item("serial", TODAY, TODAY),
                        item("Serial", TODAY.plusDays(1), TODAY),
                        item("Serial", TODAY, TODAY)));

        assertThat(ReservationCreationValidation.stable(command))
                .containsExactly(
                        new Violation("items[2].serialNumber", "must not duplicate another item serial number"),
                        new Violation("items[2].endDate", "must be on or after startDate"),
                        new Violation("items[3].serialNumber", "must not duplicate another item serial number"));
    }

    @Test
    void validatesStartAgainstSuppliedDatabaseDate() {
        ReservationCreationCommand command = new ReservationCreationCommand(
                "customer", "order", List.of(item("past", TODAY.minusDays(1), TODAY), item("today", TODAY, TODAY)));

        assertThat(ReservationCreationValidation.againstAcceptedDate(command, TODAY))
                .containsExactly(new Violation("items[0].startDate", "must be on or after the current UTC date"));
    }

    @Test
    void requiresOneCanonicalVersionFourKey() {
        UUID key = IdempotencyKeyParser.parse(List.of("F47AC10B-58CC-4372-A567-0E02B2C3D479"));
        assertThat(key.toString()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeyParser.parse(List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IdempotencyKeyParser.parse(List.of(key.toString(), key.toString())));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IdempotencyKeyParser.parse(List.of("f47ac10b-58cc-1372-a567-0e02b2c3d479")));
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeyParser.parse(List.of(" " + key)));
    }

    @Test
    void fingerprintsSemanticContentAndOrder() {
        ReservationCreationCommand first = new ReservationCreationCommand(
                "customer", "order", List.of(item("A", TODAY, TODAY), item("B", TODAY, TODAY.plusDays(1))));
        ReservationCreationCommand same = new ReservationCreationCommand(
                "customer", "order", List.of(item("A", TODAY, TODAY), item("B", TODAY, TODAY.plusDays(1))));
        ReservationCreationCommand reordered = new ReservationCreationCommand(
                "customer", "order", List.of(item("B", TODAY, TODAY.plusDays(1)), item("A", TODAY, TODAY)));

        assertThat(IdempotencyFingerprint.of(first))
                .isEqualTo(IdempotencyFingerprint.of(same))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(IdempotencyFingerprint.of(reordered));
    }

    private ReservationCreationCommand.Item item(String serial, LocalDate startDate, LocalDate endDate) {
        return new ReservationCreationCommand.Item(serial, startDate, endDate);
    }
}
