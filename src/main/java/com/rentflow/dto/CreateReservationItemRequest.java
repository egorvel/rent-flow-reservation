package com.rentflow.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One item and its inclusive reservation period.")
public record CreateReservationItemRequest(
        @Schema(description = "Inventory-owned, case-sensitive serial number.", example = "DRILL-001")
        @NotNull(message = "must not be null") String serialNumber,

        @Schema(type = "string", format = "date", example = "2026-10-01")
        @NotNull(message = "must not be null") @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE)
        LocalDate startDate,

        @Schema(type = "string", format = "date", example = "2026-10-03")
        @NotNull(message = "must not be null") @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE)
        LocalDate endDate) {}
