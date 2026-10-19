package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationOutcome;
import com.rentflow.model.ReservationCreationOutcome.Failure;
import com.rentflow.model.ReservationCreationOutcome.Snapshot;
import com.rentflow.model.ReservationCreationOutcome.Violation;
import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway.ClaimResult;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static com.rentflow.service.ReservationCreationOutcomes.ACTIVE_RESERVATION_EXISTS;
import static com.rentflow.service.ReservationCreationOutcomes.IDEMPOTENCY_IN_PROGRESS;
import static com.rentflow.service.ReservationCreationOutcomes.IDEMPOTENCY_KEY_REUSED;
import static com.rentflow.service.ReservationCreationOutcomes.INVALID_INVENTORY_REFERENCE;
import static com.rentflow.service.ReservationCreationOutcomes.INVENTORY_ITEM_NOT_FOUND;
import static com.rentflow.service.ReservationCreationOutcomes.INVENTORY_ITEM_UNAVAILABLE;
import static com.rentflow.service.ReservationCreationOutcomes.VALIDATION_FAILED;

@Service
public class ReservationCreationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCreationService.class);
    private static final Set<ReservationStatus> ACTIVE_STATUSES =
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED);

    private final ReservationCreationRequestRepository creationRequests;
    private final ReservationRepository reservations;
    private final InventoryGateway inventory;
    private final MeterRegistry metrics;
    private final ObservationRegistry observations;
    private final Duration holdDuration;

    public ReservationCreationService(
            ReservationCreationRequestRepository creationRequests,
            ReservationRepository reservations,
            InventoryGateway inventory,
            MeterRegistry metrics,
            ObservationRegistry observations,
            ReservationRuntimeSettings settings) {
        this.creationRequests = creationRequests;
        this.reservations = reservations;
        this.inventory = inventory;
        this.metrics = metrics;
        this.observations = observations;
        this.holdDuration = settings.cancellation().expiration().holdDuration();
    }

    @Transactional
    public Result create(UUID key, ReservationCreationCommand command) {
        metrics.summary("reservation.creation.batch.size")
                .record(command.items().size());
        Observation observation = Observation.start("reservation.creation", observations);
        try (Observation.Scope ignored = observation.openScope()) {
            Result result = execute(key, command);
            observation.lowCardinalityKeyValue("outcome", executionOutcome(result));
            observation.lowCardinalityKeyValue("replayed", Boolean.toString(result.replayed()));
            return result;
        } catch (RuntimeException | Error failure) {
            observation.lowCardinalityKeyValue(
                    "outcome",
                    failure instanceof InventoryGateway.ServiceUnavailableException
                            ? "unavailable"
                            : failure instanceof InventoryGateway.ProtocolException ? "protocol_error" : "error");
            observation.lowCardinalityKeyValue("replayed", "false");
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private Result execute(UUID key, ReservationCreationCommand command) {
        String fingerprint = IdempotencyFingerprint.of(command);
        if (!creationRequests.tryExecutionLock(IdempotencyFingerprint.lockId(key))) {
            count("busy");
            return transientResult(ReservationCreationOutcomes.busy());
        }

        ReservationCreationRequest request =
                creationRequests.findForUpdateByIdempotencyKey(key).orElse(null);
        Instant databaseTime = creationRequests.databaseTime();
        if (request != null && request.getExpiresAt().isAfter(databaseTime)) {
            if (!request.getFingerprint().equals(fingerprint)) {
                count("mismatch");
                return transientResult(ReservationCreationOutcomes.keyReused());
            }
            count("replay");
            return result(request.getOutcome(), request.getExpiresAt(), true);
        }

        count("attempt");
        LocalDate currentDate = creationRequests.databaseUtcDate();
        List<Violation> violations = validate(command, currentDate);
        if (!violations.isEmpty()) {
            return complete(key, fingerprint, request, ReservationCreationOutcomes.validation(violations));
        }

        List<Failure> conflicts = activeFailures(command, currentDate);
        if (!conflicts.isEmpty()) {
            return complete(key, fingerprint, request, ReservationCreationOutcomes.active(conflicts));
        }

        ClaimResult inventoryResult = inventory.claim(key, serialNumbers(command));
        return switch (inventoryResult.type()) {
            case CLAIMED -> completeSuccess(key, fingerprint, request, command);
            case BUSY -> transientResult(ReservationCreationOutcomes.busy());
            case MISSING ->
                complete(
                        key,
                        fingerprint,
                        request,
                        ReservationCreationOutcomes.inventoryMissing(inventoryResult.failures()));
            case UNAVAILABLE ->
                complete(
                        key,
                        fingerprint,
                        request,
                        ReservationCreationOutcomes.inventoryUnavailable(inventoryResult.failures()));
            case INVALID_REFERENCE ->
                complete(
                        key,
                        fingerprint,
                        request,
                        ReservationCreationOutcomes.invalidInventoryReference(inventoryResult.failures()));
            case KEY_REUSED -> complete(key, fingerprint, request, ReservationCreationOutcomes.keyReused());
        };
    }

    private List<Violation> validate(ReservationCreationCommand command, LocalDate currentDate) {
        List<Violation> violations = new ArrayList<>(ReservationCreationValidation.stable(command));
        violations.addAll(ReservationCreationValidation.againstAcceptedDate(command, currentDate));
        return List.copyOf(violations);
    }

    private List<Failure> activeFailures(ReservationCreationCommand command, LocalDate currentDate) {
        List<Reservation> activeReservations =
                reservations.findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(
                        new LinkedHashSet<>(serialNumbers(command)), ACTIVE_STATUSES, currentDate);
        Set<String> activeSerialNumbers = new LinkedHashSet<>();
        for (Reservation reservation : activeReservations) {
            activeSerialNumbers.add(reservation.getSerialNumber());
        }
        List<Failure> failures = new ArrayList<>();
        for (int index = 0; index < command.items().size(); index++) {
            String serialNumber = command.items().get(index).serialNumber();
            if (activeSerialNumbers.contains(serialNumber)) {
                failures.add(new Failure(
                        index,
                        serialNumber,
                        ACTIVE_RESERVATION_EXISTS,
                        "An active reservation already exists for this item."));
            }
        }
        return List.copyOf(failures);
    }

    private List<String> serialNumbers(ReservationCreationCommand command) {
        return command.items().stream()
                .map(ReservationCreationCommand.Item::serialNumber)
                .toList();
    }

    private Result completeSuccess(
            UUID key, String fingerprint, ReservationCreationRequest request, ReservationCreationCommand command) {
        Instant creationTime = creationRequests.databaseTime();
        Instant holdExpiresAt = creationTime.plus(holdDuration);
        List<Reservation> created = command.items().stream()
                .map(item -> new Reservation(
                        item.serialNumber(),
                        command.customerId(),
                        command.orderId(),
                        item.startDate(),
                        item.endDate(),
                        creationTime,
                        holdExpiresAt))
                .toList();
        List<Reservation> saved = reservations.saveAllAndFlush(created);
        List<Snapshot> snapshots = saved.stream()
                .map(reservation -> new Snapshot(
                        reservation.getId(),
                        reservation.getSerialNumber(),
                        reservation.getCustomerId(),
                        reservation.getOrderId(),
                        reservation.getStartDate(),
                        reservation.getEndDate(),
                        reservation.getTimestamp(),
                        reservation.getHoldExpiresAt(),
                        reservation.getStatus().name()))
                .toList();
        return complete(key, fingerprint, request, ReservationCreationOutcomes.success(snapshots));
    }

    private Result complete(
            UUID key, String fingerprint, ReservationCreationRequest request, ReservationCreationOutcome outcome) {
        ReservationCreationRequest completed = request == null ? new ReservationCreationRequest(key) : request;
        completed.complete(fingerprint, outcome, creationRequests.databaseTime());
        creationRequests.saveAndFlush(completed);
        count("completion");
        String committedOutcome = terminalOutcome(outcome.code());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    metrics.counter("reservation.creation.committed", "outcome", committedOutcome)
                            .increment();
                } catch (RuntimeException failure) {
                    LOGGER.warn("Committed creation telemetry could not be recorded.", failure);
                }
            }
        });
        return result(outcome, completed.getExpiresAt(), false);
    }

    private String executionOutcome(Result result) {
        if (IDEMPOTENCY_IN_PROGRESS.equals(result.code())) {
            return "busy";
        }
        if (IDEMPOTENCY_KEY_REUSED.equals(result.code()) && result.expiresAt() == null) {
            return "key_mismatch";
        }
        return terminalOutcome(result.code());
    }

    private String terminalOutcome(String code) {
        return switch (code) {
            case null -> "created";
            case VALIDATION_FAILED -> "validation_failed";
            case ACTIVE_RESERVATION_EXISTS -> "active_conflict";
            case INVENTORY_ITEM_NOT_FOUND -> "inventory_missing";
            case INVENTORY_ITEM_UNAVAILABLE -> "inventory_unavailable";
            case INVALID_INVENTORY_REFERENCE -> "invalid_reference";
            case IDEMPOTENCY_KEY_REUSED -> "key_reused";
            default -> "error";
        };
    }

    private Result transientResult(ReservationCreationOutcome outcome) {
        return result(outcome, null, false);
    }

    private Result result(ReservationCreationOutcome outcome, Instant expiresAt, boolean replayed) {
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
        return new Result(outcome.status(), body, outcome.code(), expiresAt, replayed);
    }

    private void count(String outcome) {
        metrics.counter("reservation.creation.idempotency", "outcome", outcome).increment();
    }

    public record Result(int status, Object body, String code, Instant expiresAt, boolean replayed) {}
}
