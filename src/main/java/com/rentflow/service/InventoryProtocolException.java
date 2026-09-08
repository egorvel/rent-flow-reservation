package com.rentflow.service;

public class InventoryProtocolException extends RuntimeException {
    public InventoryProtocolException(Throwable cause) {
        super("Inventory returned an unexpected response", cause);
    }
}
