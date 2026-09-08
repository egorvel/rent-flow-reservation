package com.rentflow.service.rest;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;

@HttpExchange("/api/v1/inventory")
public interface InventoryHttpClient {
    @PatchExchange("/status")
    ResponseEntity<Void> reserve(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody List<InventoryStatusChangeRequest> changes);

    record InventoryStatusChangeRequest(String serialNumber, String status) {}
}
