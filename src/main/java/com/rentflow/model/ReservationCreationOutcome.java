package com.rentflow.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationCreationOutcome(
        int status,
        String type,
        String title,
        String detail,
        String instance,
        String code,
        List<Snapshot> reservations,
        List<Failure> failedItems,
        List<Violation> violations) {
    public ReservationCreationOutcome {
        reservations = reservations == null ? List.of() : List.copyOf(reservations);
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public record Snapshot(
            UUID id,
            String serialNumber,
            String customerId,
            String orderId,
            LocalDate startDate,
            LocalDate endDate,
            Instant timestamp,
            Instant holdExpiresAt,
            String status) {}

    public record Failure(int index, String serialNumber, String code, String message) {}

    public record Violation(String field, String message) {}
}
