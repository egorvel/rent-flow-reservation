package com.rentflow.service;

public class ReservationCreationLeaseLostException extends RuntimeException {
    public ReservationCreationLeaseLostException() {
        super("Reservation creation lease is no longer owned");
    }
}
