package com.rentflow.model;

import java.time.Instant;
import java.util.UUID;

public record ReservationCreationPreparation(
        Type type,
        UUID idempotencyKey,
        UUID leaseOwner,
        ReservationCreationState state,
        ReservationCreationCommand command,
        ReservationCreationOutcome outcome,
        Instant expiresAt,
        int attemptCount) {
    public enum Type {
        EXECUTE,
        REPLAY,
        BUSY,
        MISMATCH,
        RECONCILIATION
    }
}
