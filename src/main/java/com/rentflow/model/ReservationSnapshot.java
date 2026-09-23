package com.rentflow.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReservationSnapshot(
        UUID id,
        String serialNumber,
        String customerId,
        String orderId,
        LocalDate startDate,
        LocalDate endDate,
        Instant timestamp,
        Instant holdExpiresAt,
        String status) {}
