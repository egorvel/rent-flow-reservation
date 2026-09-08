package com.rentflow.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.rentflow.model.InventoryClaimResult;
import com.rentflow.model.ReservationCommandViolation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationOutcome;
import com.rentflow.model.ReservationCreationPreparation;
import com.rentflow.model.ReservationCreationResult;
import com.rentflow.model.ReservationCreationState;

@Service
public class ReservationCreationService {
    private static final Duration BUSY_RETRY = Duration.ofSeconds(1);

    private final ReservationCreationStoreService store;
    private final InventoryGateway inventoryGateway;
    private final ReservationCreationSettings settings;
    private final ReservationCreationMetricsService metrics;

    public ReservationCreationService(
            ReservationCreationStoreService store,
            InventoryGateway inventoryGateway,
            ReservationCreationSettings settings,
            ReservationCreationMetricsService metrics) {
        this.store = store;
        this.inventoryGateway = inventoryGateway;
        this.settings = settings;
        this.metrics = metrics;
    }

    private ReservationCreationResult start(UUID key, ReservationCreationCommand command) {
        List<ReservationCommandViolation> violations = ReservationCreationValidation.stable(command);
        if (!violations.isEmpty()) {
            throw new ReservationCreationValidationException(violations);
        }

        UUID owner = UUID.randomUUID();
        ReservationCreationPreparation preparation =
                store.prepare(key, IdempotencyFingerprint.of(command), command, owner);
        return switch (preparation.type()) {
            case REPLAY -> new ReservationCreationResult(preparation.outcome(), true, preparation.expiresAt());
            case RECONCILIATION -> new ReservationCreationResult(preparation.outcome(), false, null);
            case MISMATCH -> result(ReservationCreationOutcomes.keyReused());
            case BUSY -> new ReservationCreationResult(ReservationCreationOutcomes.busy(), false, null);
            case EXECUTE -> execute(preparation);
        };
    }

    public ReservationCreationHttpResponse create(UUID key, ReservationCreationCommand command) {
        ReservationCreationResult result = start(key, command);
        ReservationCreationOutcome outcome = result.outcome();
        metrics.request(metricOutcome(result));
        Object body;
        if (outcome.status() == 201) {
            body = outcome.reservations();
        } else {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", outcome.type());
            problem.put("title", outcome.title());
            problem.put("status", outcome.status());
            problem.put("detail", outcome.detail());
            problem.put("instance", outcome.instance());
            problem.put("code", outcome.code());
            if (!outcome.failedItems().isEmpty()) {
                problem.put("failedItems", outcome.failedItems());
            }
            if (!outcome.violations().isEmpty()) {
                problem.put("violations", outcome.violations());
            }
            body = problem;
        }
        return new ReservationCreationHttpResponse(
                outcome.status(), body, outcome.code(), result.replayed(), result.expiresAt());
    }

    private String metricOutcome(ReservationCreationResult result) {
        if (result.replayed()) {
            return "replay";
        }
        String code = result.outcome().code();
        if (code == null) {
            return "completion";
        }
        return switch (code) {
            case "IDEMPOTENCY_KEY_REUSED" -> "mismatch";
            case "IDEMPOTENCY_IN_PROGRESS" -> "busy";
            case "ACTIVE_RESERVATION_EXISTS" -> "active-conflict";
            case "INVENTORY_ITEM_NOT_FOUND", "INVENTORY_ITEM_UNAVAILABLE", "INVALID_INVENTORY_REFERENCE" ->
                "inventory-rejection";
            case "INVENTORY_SERVICE_UNAVAILABLE" -> "unavailable";
            case "INVENTORY_SERVICE_ERROR", "CREATION_RECONCILIATION_REQUIRED" -> "reconciliation";
            default -> "other";
        };
    }

    ReservationCreationResult execute(ReservationCreationPreparation preparation) {
        try {
            ReservationCreationPreparation current = preparation;
            if (current.state() == ReservationCreationState.PENDING_LOCAL_CHECK) {
                current = store.admitLocal(current.idempotencyKey(), current.leaseOwner());
                if (current.state() == ReservationCreationState.COMPLETED) {
                    return new ReservationCreationResult(current.outcome(), false, current.expiresAt());
                }
            }
            return executeInventory(current);
        } catch (ReservationCreationLeaseLostException exception) {
            return new ReservationCreationResult(ReservationCreationOutcomes.busy(), false, null);
        }
    }

    private ReservationCreationResult executeInventory(ReservationCreationPreparation preparation) {
        Optional<ReservationCreationResult> deadline =
                store.authorizeInventoryAttempt(preparation.idempotencyKey(), preparation.leaseOwner());
        if (deadline.isPresent()) {
            return deadline.orElseThrow();
        }

        List<String> serialNumbers = preparation.command().items().stream()
                .map(ReservationCreationCommand.Item::serialNumber)
                .toList();
        try {
            InventoryClaimResult inventory = inventoryGateway.claim(preparation.idempotencyKey(), serialNumbers);
            return switch (inventory.type()) {
                case CLAIMED -> store.finalizeSuccess(preparation.idempotencyKey(), preparation.leaseOwner());
                case BUSY -> releaseBusy(preparation);
                case MISSING, UNAVAILABLE, INVALID_REFERENCE, KEY_REUSED ->
                    store.completeInventoryResult(preparation.idempotencyKey(), preparation.leaseOwner(), inventory);
            };
        } catch (InventoryServiceUnavailableException exception) {
            Duration delay = recoveryDelay(preparation.attemptCount());
            store.releaseForRetry(preparation.idempotencyKey(), preparation.leaseOwner(), delay);
            return result(ReservationCreationOutcomes.inventoryServiceUnavailable());
        } catch (InventoryProtocolException exception) {
            return store.reconcileInventoryProtocol(preparation.idempotencyKey(), preparation.leaseOwner());
        }
    }

    private ReservationCreationResult releaseBusy(ReservationCreationPreparation preparation) {
        store.releaseForRetry(preparation.idempotencyKey(), preparation.leaseOwner(), BUSY_RETRY);
        return new ReservationCreationResult(ReservationCreationOutcomes.busy(), false, null);
    }

    private Duration recoveryDelay(int previousAttempts) {
        Duration initial = settings.recoveryInitialBackoff();
        Duration maximum = settings.recoveryMaxBackoff();
        int exponent = Math.min(previousAttempts, 20);
        long multiplier = 1L << exponent;
        long baseMillis;
        try {
            baseMillis = Math.multiplyExact(initial.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            baseMillis = maximum.toMillis();
        }
        baseMillis = Math.min(baseMillis, maximum.toMillis());
        double jitter = settings.recoveryJitterFactor();
        double factor = jitter == 0 ? 1 : 1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Duration.ofMillis(Math.max(1, Math.round(baseMillis * factor)));
    }

    private ReservationCreationResult result(ReservationCreationOutcome outcome) {
        return new ReservationCreationResult(outcome, false, null);
    }
}
