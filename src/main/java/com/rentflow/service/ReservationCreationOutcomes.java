package com.rentflow.service;

import java.util.List;

import com.rentflow.model.ReservationCreationOutcome;
import com.rentflow.model.ReservationCreationOutcome.Failure;
import com.rentflow.model.ReservationCreationOutcome.Snapshot;
import com.rentflow.model.ReservationCreationOutcome.Violation;

public final class ReservationCreationOutcomes {
    public static final String PATH = "/api/v1/reservations";
    public static final String ACTIVE_RESERVATION_EXISTS = "ACTIVE_RESERVATION_EXISTS";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String INVENTORY_ITEM_NOT_FOUND = "INVENTORY_ITEM_NOT_FOUND";
    public static final String INVENTORY_ITEM_UNAVAILABLE = "INVENTORY_ITEM_UNAVAILABLE";
    public static final String INVALID_INVENTORY_REFERENCE = "INVALID_INVENTORY_REFERENCE";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

    private ReservationCreationOutcomes() {}

    public static ReservationCreationOutcome success(List<Snapshot> reservations) {
        return new ReservationCreationOutcome(201, null, null, null, PATH, null, reservations, List.of(), List.of());
    }

    public static ReservationCreationOutcome active(List<Failure> failures) {
        return problem(
                409,
                "active-reservation-exists",
                "Active reservation exists",
                "No reservations were created.",
                ACTIVE_RESERVATION_EXISTS,
                failures,
                List.of());
    }

    public static ReservationCreationOutcome validation(List<Violation> violations) {
        return problem(
                400,
                "validation-failed",
                "Request validation failed",
                "One or more request values are invalid.",
                VALIDATION_FAILED,
                List.of(),
                violations);
    }

    public static ReservationCreationOutcome inventoryMissing(List<Failure> failures) {
        return problem(
                422,
                "inventory-item-not-found",
                "Inventory item not found",
                "Inventory could not authorize the reservation batch.",
                INVENTORY_ITEM_NOT_FOUND,
                failures,
                List.of());
    }

    public static ReservationCreationOutcome inventoryUnavailable(List<Failure> failures) {
        return problem(
                409,
                "inventory-item-unavailable",
                "Inventory item unavailable",
                "Inventory could not authorize the reservation batch.",
                INVENTORY_ITEM_UNAVAILABLE,
                failures,
                List.of());
    }

    public static ReservationCreationOutcome invalidInventoryReference(List<Failure> failures) {
        List<Violation> violations = failures.stream()
                .map(failure -> new Violation(
                        "items[" + failure.index() + "].serialNumber", "is not a valid Inventory reference"))
                .toList();
        return problem(
                400,
                "invalid-inventory-reference",
                "Invalid Inventory reference",
                "Inventory rejected one or more serial numbers.",
                INVALID_INVENTORY_REFERENCE,
                List.of(),
                violations);
    }

    public static ReservationCreationOutcome keyReused() {
        return problem(
                422,
                "idempotency-key-reused",
                "Idempotency key reused",
                "The idempotency key is already associated with another request.",
                IDEMPOTENCY_KEY_REUSED,
                List.of(),
                List.of());
    }

    public static ReservationCreationOutcome busy() {
        return problem(
                409,
                "idempotency-in-progress",
                "Idempotency key in progress",
                "A request with this idempotency key is already in progress.",
                IDEMPOTENCY_IN_PROGRESS,
                List.of(),
                List.of());
    }

    private static ReservationCreationOutcome problem(
            int status,
            String typeSuffix,
            String title,
            String detail,
            String code,
            List<Failure> failures,
            List<Violation> violations) {
        return new ReservationCreationOutcome(
                status,
                "urn:rentflow:problem:" + typeSuffix,
                title,
                detail,
                PATH,
                code,
                List.of(),
                failures,
                violations);
    }
}
