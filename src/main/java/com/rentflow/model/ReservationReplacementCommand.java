package com.rentflow.model;

import java.time.LocalDate;
import java.util.Objects;

/** Mutable reservation details only; replacement cannot assign or change the owner. */
public record ReservationReplacementCommand(
        String serialNumber, String orderId, LocalDate startDate, LocalDate endDate, ReservationStatus status) {
    public ReservationReplacementCommand {
        Objects.requireNonNull(serialNumber);
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(startDate);
        Objects.requireNonNull(endDate);
        Objects.requireNonNull(status);
    }
}
