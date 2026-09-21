package com.rentflow.model;

public record ReservationCancellationEvent(
        String eventId, String eventType, int eventVersion, String occurredAt, String serialNumber) {}
