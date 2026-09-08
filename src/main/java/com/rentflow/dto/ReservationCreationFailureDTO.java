package com.rentflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A requested item that prevented the atomic batch from being created.")
public record ReservationCreationFailureDTO(
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int index,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String serialNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
