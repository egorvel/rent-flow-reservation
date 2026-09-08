package com.rentflow.model;

public enum ReservationCreationState {
    PENDING_LOCAL_CHECK,
    PENDING_INVENTORY,
    COMPLETED,
    RECONCILIATION_REQUIRED
}
