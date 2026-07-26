package com.rentflow.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Creation details; the server assigns ID, timestamp and HELD status.", example = """
{
  "serialNumber": "DRILL-001",
  "customerId": "CUSTOMER-001",
  "orderId": "ORDER-001",
  "startDate": "2026-10-01",
  "endDate": "2026-10-03"
}
""")
public record CreateReservationRequest(
        @Schema(
                description = "Case-sensitive equipment serial number; repeated values are allowed.",
                example = "DRILL-001",
                minLength = 1,
                maxLength = 64,
                pattern = CreateReservationRequest.SERIAL_NUMBER_PATTERN,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Pattern(regexp = CreateReservationRequest.SERIAL_NUMBER_PATTERN, message = "must be a valid serial number") String serialNumber,

        @Schema(
                description = "Opaque client-assigned customer identifier; no existence or uniqueness check.",
                example = "CUSTOMER-001",
                minLength = 1,
                maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Size(max = 64, message = "must have at most 64 characters") String customerId,

        @Schema(
                description = "Opaque order identifier shared by related reservations.",
                example = "ORDER-001",
                minLength = 1,
                maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Size(max = 64, message = "must have at most 64 characters") String orderId,

        @Schema(
                description = "Inclusive start date (years 0001–9999); historical dates are allowed.",
                example = "2026-10-01",
                type = "string",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE)
        LocalDate startDate,

        @Schema(
                description = "Inclusive end date (years 0001–9999), on or after startDate.",
                example = "2026-10-03",
                type = "string",
                format = "date",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE)
        LocalDate endDate) {
    public static final String SERIAL_NUMBER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
}
