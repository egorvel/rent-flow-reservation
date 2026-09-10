package com.rentflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCommandViolation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationFailure;
import com.rentflow.model.ReservationCreationOutcome;
import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationSnapshot;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway.ClaimResult;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ReservationCreationService {
    private static final Set<ReservationStatus> ACTIVE_STATUSES =
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED);

    private final ReservationCreationRequestRepository creationRequests;
    private final ReservationRepository reservations;
    private final InventoryGateway inventory;
    private final MeterRegistry metrics;

    public ReservationCreationService(
            ReservationCreationRequestRepository creationRequests,
            ReservationRepository reservations,
            InventoryGateway inventory,
            MeterRegistry metrics) {
        this.creationRequests = creationRequests;
        this.reservations = reservations;
        this.inventory = inventory;
        this.metrics = metrics;
    }

    @Transactional
    public Result create(UUID key, ReservationCreationCommand command) {
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
        List<ReservationCommandViolation> violations = validate(command, currentDate);
        if (!violations.isEmpty()) {
            return complete(key, fingerprint, request, ReservationCreationOutcomes.validation(violations));
        }

        List<ReservationCreationFailure> conflicts = activeFailures(command, currentDate);
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

    private List<ReservationCommandViolation> validate(ReservationCreationCommand command, LocalDate currentDate) {
        List<ReservationCommandViolation> violations = new ArrayList<>(ReservationCreationValidation.stable(command));
        violations.addAll(ReservationCreationValidation.againstAcceptedDate(command, currentDate));
        return List.copyOf(violations);
    }

    private List<ReservationCreationFailure> activeFailures(ReservationCreationCommand command, LocalDate currentDate) {
        List<Reservation> activeReservations =
                reservations.findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(
                        new LinkedHashSet<>(serialNumbers(command)), ACTIVE_STATUSES, currentDate);
        Set<String> activeSerialNumbers = new LinkedHashSet<>();
        for (Reservation reservation : activeReservations) {
            activeSerialNumbers.add(reservation.getSerialNumber());
        }
        List<ReservationCreationFailure> failures = new ArrayList<>();
        for (int index = 0; index < command.items().size(); index++) {
            String serialNumber = command.items().get(index).serialNumber();
            if (activeSerialNumbers.contains(serialNumber)) {
                failures.add(new ReservationCreationFailure(
                        index,
                        serialNumber,
                        "ACTIVE_RESERVATION_EXISTS",
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
        List<Reservation> created = command.items().stream()
                .map(item -> new Reservation(
                        item.serialNumber(), command.customerId(), command.orderId(), item.startDate(), item.endDate()))
                .toList();
        List<Reservation> saved = reservations.saveAllAndFlush(created);
        List<ReservationSnapshot> snapshots = saved.stream()
                .map(reservation -> new ReservationSnapshot(
                        reservation.getId(),
                        reservation.getSerialNumber(),
                        reservation.getCustomerId(),
                        reservation.getOrderId(),
                        reservation.getStartDate(),
                        reservation.getEndDate(),
                        reservation.getTimestamp(),
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
        return result(outcome, completed.getExpiresAt(), false);
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
