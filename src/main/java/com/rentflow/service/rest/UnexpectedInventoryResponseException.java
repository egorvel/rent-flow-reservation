package com.rentflow.service.rest;

import org.springframework.http.HttpStatusCode;

public class UnexpectedInventoryResponseException extends RuntimeException {
    public UnexpectedInventoryResponseException(HttpStatusCode status) {
        super("Unexpected Inventory status " + status.value());
    }

    public UnexpectedInventoryResponseException(Throwable cause) {
        super("Unexpected Inventory response", cause);
    }
}
