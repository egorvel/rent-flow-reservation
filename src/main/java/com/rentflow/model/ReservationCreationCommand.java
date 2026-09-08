package com.rentflow.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ReservationCreationCommand(String customerId, String orderId, List<Item> items) {
    public ReservationCreationCommand {
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(orderId);
        items = List.copyOf(items);
    }

    public record Item(String serialNumber, LocalDate startDate, LocalDate endDate) {
        public Item {
            Objects.requireNonNull(serialNumber);
            Objects.requireNonNull(startDate);
            Objects.requireNonNull(endDate);
        }
    }
}
