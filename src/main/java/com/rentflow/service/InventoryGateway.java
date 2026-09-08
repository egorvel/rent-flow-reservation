package com.rentflow.service;

import java.util.List;
import java.util.UUID;

import com.rentflow.model.InventoryClaimResult;

public interface InventoryGateway {
    InventoryClaimResult claim(UUID idempotencyKey, List<String> serialNumbers);
}
