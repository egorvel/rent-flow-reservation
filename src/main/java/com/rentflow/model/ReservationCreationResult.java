package com.rentflow.model;

import java.time.Instant;

public record ReservationCreationResult(ReservationCreationOutcome outcome, boolean replayed, Instant expiresAt) {}
