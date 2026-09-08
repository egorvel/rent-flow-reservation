package com.rentflow.service;

public class InventoryServiceUnavailableException extends RuntimeException {
    public InventoryServiceUnavailableException(Throwable cause) {
        super("Inventory is unavailable", cause);
    }
}
