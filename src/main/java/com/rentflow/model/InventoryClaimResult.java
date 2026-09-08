package com.rentflow.model;

import java.util.List;

public record InventoryClaimResult(Type type, List<ReservationCreationFailure> failures) {
    public InventoryClaimResult {
        failures = List.copyOf(failures);
    }

    public static InventoryClaimResult claimed() {
        return new InventoryClaimResult(Type.CLAIMED, List.of());
    }

    public static InventoryClaimResult terminal(Type type, List<ReservationCreationFailure> failures) {
        return new InventoryClaimResult(type, failures);
    }

    public static InventoryClaimResult busy() {
        return new InventoryClaimResult(Type.BUSY, List.of());
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
