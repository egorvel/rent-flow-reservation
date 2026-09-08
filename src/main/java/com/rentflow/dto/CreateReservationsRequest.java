package com.rentflow.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Common order data and a bounded batch of reservation items.", example = """
        {
          "customerId": "CUSTOMER-001",
          "orderId": "ORDER-001",
          "items": [
            {"serialNumber": "DRILL-001", "startDate": "2026-10-01", "endDate": "2026-10-03"}
          ]
        }
        """)
public record CreateReservationsRequest(
        @NotBlank(message = "must not be blank") @Size(max = 64, message = "must have at most 64 characters") String customerId,

        @NotBlank(message = "must not be blank") @Size(max = 64, message = "must have at most 64 characters") String orderId,

        @NotNull(message = "must not be null") @Size(min = 1, max = 100, message = "must contain between 1 and 100 items") List<@NotNull(message = "must not be null") @Valid CreateReservationItemRequest> items) {}
