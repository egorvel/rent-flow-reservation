package com.rentflow.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationOutcome.Violation;

public final class ReservationCreationValidation {
    private ReservationCreationValidation() {}

    public static List<Violation> stable(ReservationCreationCommand command) {
        List<Violation> violations = new ArrayList<>();
        Set<String> serialNumbers = new HashSet<>();
        for (int index = 0; index < command.items().size(); index++) {
            ReservationCreationCommand.Item item = command.items().get(index);
            String prefix = "items[" + index + "]";
            if (!serialNumbers.add(item.serialNumber())) {
                violations.add(
                        new Violation(prefix + ".serialNumber", "must not duplicate another item serial number"));
            }
            validateYear(item.startDate(), prefix + ".startDate", violations);
            validateYear(item.endDate(), prefix + ".endDate", violations);
            if (item.endDate().isBefore(item.startDate())) {
                violations.add(new Violation(prefix + ".endDate", "must be on or after startDate"));
            }
        }
        return List.copyOf(violations);
    }

    public static List<Violation> againstAcceptedDate(ReservationCreationCommand command, LocalDate acceptedDate) {
        List<Violation> violations = new ArrayList<>();
        for (int index = 0; index < command.items().size(); index++) {
            ReservationCreationCommand.Item item = command.items().get(index);
            if (item.startDate().isBefore(acceptedDate)) {
                violations.add(
                        new Violation("items[" + index + "].startDate", "must be on or after the current UTC date"));
            }
        }
        return List.copyOf(violations);
    }

    private static void validateYear(LocalDate date, String field, List<Violation> violations) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            violations.add(new Violation(field, "year must be between 0001 and 9999"));
        }
    }
}
