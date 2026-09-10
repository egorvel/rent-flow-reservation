package com.rentflow.service;

import java.util.List;
import java.util.UUID;

import com.rentflow.model.ReservationCreationFailure;

public interface InventoryGateway {
    ClaimResult claim(UUID idempotencyKey, List<String> serialNumbers);

    record ClaimResult(Type type, List<ReservationCreationFailure> failures) {
        public ClaimResult {
            failures = List.copyOf(failures);
        }

        public static ClaimResult claimed() {
            return new ClaimResult(Type.CLAIMED, List.of());
        }

        public static ClaimResult terminal(Type type, List<ReservationCreationFailure> failures) {
            return new ClaimResult(type, failures);
        }

        public static ClaimResult busy() {
            return new ClaimResult(Type.BUSY, List.of());
        }

        public enum Type {
            CLAIMED,
            MISSING,
            UNAVAILABLE,
            INVALID_REFERENCE,
            BUSY,
            KEY_REUSED
        }
    }
}
