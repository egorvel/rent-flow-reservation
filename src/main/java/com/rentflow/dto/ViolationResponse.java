package com.rentflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One rejected request field and its validation message.")
public record ViolationResponse(
        @Schema(
                description = "Rejected request field.",
                example = "endDate",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String field,

        @Schema(
                description = "Stable validation message.",
                example = "must be on or after startDate",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {}
