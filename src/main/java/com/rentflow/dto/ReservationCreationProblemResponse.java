package com.rentflow.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RFC 9457 Problem Details for an atomically rejected reservation batch.")
public record ReservationCreationProblemResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        List<ReservationCreationFailureDTO> failedItems) {}
