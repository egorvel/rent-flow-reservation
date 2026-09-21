package com.rentflow.model;

public enum ReservationCancellationFailureCode {
    KAFKA_SEND_TIMEOUT,
    KAFKA_SEND_FAILED,
    RELAY_INTERRUPTED
}
