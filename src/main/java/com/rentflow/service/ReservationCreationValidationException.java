package com.rentflow.service;

import java.util.List;

import com.rentflow.model.ReservationCommandViolation;

public class ReservationCreationValidationException extends RuntimeException {
    private final List<Violation> violations;

    public ReservationCreationValidationException(List<ReservationCommandViolation> violations) {
        super("Reservation creation request is invalid");
        this.violations = violations.stream()
                .map(violation -> new Violation(violation.field(), violation.message()))
                .toList();
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public record Violation(String field, String message) {}
}
