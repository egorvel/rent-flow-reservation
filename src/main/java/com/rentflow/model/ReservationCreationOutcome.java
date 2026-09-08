package com.rentflow.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationCreationOutcome(
        int status,
        String type,
        String title,
        String detail,
        String instance,
        String code,
        List<ReservationSnapshot> reservations,
        List<ReservationCreationFailure> failedItems,
        List<ReservationCommandViolation> violations) {
    public ReservationCreationOutcome {
        reservations = reservations == null ? List.of() : List.copyOf(reservations);
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
