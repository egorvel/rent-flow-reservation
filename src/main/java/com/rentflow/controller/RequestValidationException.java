package com.rentflow.controller;

import java.util.List;

import com.rentflow.dto.ViolationResponse;

class RequestValidationException extends RuntimeException {

    private final List<ViolationResponse> violations;

    RequestValidationException(String field, String message) {
        super("Invalid request value");
        this.violations = List.of(new ViolationResponse(field, message));
    }

    List<ViolationResponse> getViolations() {
        return violations;
    }
}
