package com.rentflow.model;

public record ReservationCreationFailure(int index, String serialNumber, String code, String message) {}
