package com.rentflow.service;

import java.util.Arrays;

public enum ReservationSortField {
    ID("id"),
    SERIAL_NUMBER("serialNumber"),
    CUSTOMER_ID("customerId"),
    ORDER_ID("orderId"),
    START_DATE("startDate"),
    END_DATE("endDate"),
    TIMESTAMP("timestamp"),
    STATUS("status");

    private final String property;

    ReservationSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static ReservationSortField fromApiName(String name) {
        return Arrays.stream(values())
                .filter(field -> field.property.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported reservation sort field"));
    }
}
