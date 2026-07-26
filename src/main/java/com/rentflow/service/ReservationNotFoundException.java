package com.rentflow.service;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    private final UUID id;

    public ReservationNotFoundException(UUID id) {
        super("Reservation not found");
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
