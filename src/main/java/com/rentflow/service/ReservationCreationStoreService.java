package com.rentflow.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.InventoryClaimResult;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCommandViolation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationFailure;
import com.rentflow.model.ReservationCreationOutcome;
import com.rentflow.model.ReservationCreationPreparation;
import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationCreationResult;
import com.rentflow.model.ReservationCreationState;
import com.rentflow.model.ReservationSnapshot;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationCreationStoreService {
    private static final Set<ReservationStatus> ACTIVE_STATUSES =
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED);

    private final ReservationCreationRequestRepository workflowRepository;
    private final ReservationRepository reservationRepository;
    private final DatabaseTimeProvider databaseTimeProvider;
    private final ReservationCreationSettings settings;
    private final ObjectMapper objectMapper;

    public ReservationCreationStoreService(
            ReservationCreationRequestRepository workflowRepository,
            ReservationRepository reservationRepository,
            DatabaseTimeProvider databaseTimeProvider,
            ReservationCreationSettings settings,
            ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.reservationRepository = reservationRepository;
        this.databaseTimeProvider = databaseTimeProvider;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReservationCreationPreparation prepare(
            UUID key, String fingerprint, ReservationCreationCommand command, UUID owner) {
        DatabaseTimeSnapshot time = databaseTimeProvider.now();
        Optional<ReservationCreationRequest> existing = workflowRepository.findLockedByIdempotencyKey(key);
        if (existing.isPresent()) {
            ReservationCreationRequest request = existing.get();
            if (isLogicallyExpired(request, time.observedAt())) {
                validateAcceptedDate(command, time.utcDate());
                workflowRepository.delete(request);
                workflowRepository.flush();
                insert(key, fingerprint, command, owner, time);
                return executable(key, owner);
            }
            return prepareExisting(request, fingerprint, owner);
        }

        int inserted = insert(key, fingerprint, command, owner, time);
        if (inserted == 1) {
            validateAcceptedDate(command, time.utcDate());
            return executable(key, owner);
        }
        ReservationCreationRequest concurrent =
                workflowRepository.findLockedByIdempotencyKey(key).orElseThrow();
        return prepareExisting(concurrent, fingerprint, owner);
    }

    @Transactional
    public ReservationCreationPreparation admitLocal(UUID key, UUID owner) {
        ReservationCreationRequest request = owned(key, owner, ReservationCreationState.PENDING_LOCAL_CHECK);
        List<ReservationCreationFailure> conflicts = activeFailures(request);
        if (!conflicts.isEmpty()) {
            ReservationCreationOutcome outcome = ReservationCreationOutcomes.active(conflicts);
            completeOwned(key, owner, outcome);
            return replayable(key);
        }
        requireUpdated(workflowRepository.advanceToInventory(key, owner));
        return executable(key, owner);
    }

    @Transactional
    public Optional<ReservationCreationResult> authorizeInventoryAttempt(UUID key, UUID owner) {
        ReservationCreationRequest request = owned(key, owner, ReservationCreationState.PENDING_INVENTORY);
        DatabaseTimeSnapshot time = databaseTimeProvider.now();
        if (!time.observedAt().isBefore(request.getRecoveryDeadline())) {
            ReservationCreationOutcome outcome = ReservationCreationOutcomes.reconciliationRequired();
            reconcileOwned(key, owner, outcome);
            return Optional.of(new ReservationCreationResult(outcome, false, null));
        }
        requireUpdated(workflowRepository.incrementAttempt(key, owner));
        return Optional.empty();
    }

    @Transactional
    public ReservationCreationResult completeInventoryResult(UUID key, UUID owner, InventoryClaimResult result) {
        ReservationCreationOutcome outcome =
                switch (result.type()) {
                    case MISSING -> ReservationCreationOutcomes.inventoryMissing(result.failures());
                    case UNAVAILABLE -> ReservationCreationOutcomes.inventoryUnavailable(result.failures());
                    case INVALID_REFERENCE -> ReservationCreationOutcomes.invalidInventoryReference(result.failures());
                    case KEY_REUSED -> ReservationCreationOutcomes.keyReused();
                    default -> throw new IllegalArgumentException("Inventory outcome is not terminal");
                };
        owned(key, owner, ReservationCreationState.PENDING_INVENTORY);
        completeOwned(key, owner, outcome);
        ReservationCreationRequest completed = workflowRepository.findById(key).orElseThrow();
        return new ReservationCreationResult(outcome, false, completed.getExpiresAt());
    }

    @Transactional
    public ReservationCreationResult finalizeSuccess(UUID key, UUID owner) {
        ReservationCreationRequest request = owned(key, owner, ReservationCreationState.PENDING_INVENTORY);
        if (!activeFailures(request).isEmpty()) {
            ReservationCreationOutcome outcome = ReservationCreationOutcomes.reconciliationRequired();
            reconcileOwned(key, owner, outcome);
            return new ReservationCreationResult(outcome, false, null);
        }

        List<Reservation> reservations = request.getRequestPayload().items().stream()
                .map(item -> new Reservation(
                        item.serialNumber(),
                        request.getRequestPayload().customerId(),
                        request.getRequestPayload().orderId(),
                        item.startDate(),
                        item.endDate()))
                .toList();
        List<Reservation> saved = reservationRepository.saveAllAndFlush(reservations);
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
        ReservationCreationOutcome outcome = ReservationCreationOutcomes.success(snapshots);
        completeOwned(key, owner, outcome);
        ReservationCreationRequest completed = workflowRepository.findById(key).orElseThrow();
        return new ReservationCreationResult(outcome, false, completed.getExpiresAt());
    }

    @Transactional
    public void releaseForRetry(UUID key, UUID owner, Duration delay) {
        requireUpdated(workflowRepository.releaseForRetry(key, owner, delay.toMillis()));
    }

    @Transactional
    public ReservationCreationResult reconcileInventoryProtocol(UUID key, UUID owner) {
        owned(key, owner, ReservationCreationState.PENDING_INVENTORY);
        ReservationCreationOutcome outcome = ReservationCreationOutcomes.inventoryServiceError();
        reconcileOwned(key, owner, outcome);
        return new ReservationCreationResult(outcome, false, null);
    }

    @Transactional
    public Optional<ReservationCreationPreparation> claimForRecovery(UUID key, UUID owner) {
        int claimed =
                workflowRepository.claim(key, owner, settings.leaseDuration().toMillis(), true);
        if (claimed == 0) {
            return Optional.empty();
        }
        return Optional.of(executable(key, owner));
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueKeys() {
        return workflowRepository.findDueKeys(PageRequest.of(0, settings.recoveryBatchSize()));
    }

    @Transactional
    public int deleteExpiredCompleted(int limit) {
        return workflowRepository.deleteExpiredCompleted(limit);
    }

    @Transactional(readOnly = true)
    public long countDuePending() {
        return workflowRepository.countDuePending();
    }

    @Transactional(readOnly = true)
    public long countExpiredCompleted() {
        return workflowRepository.countExpiredCompleted();
    }

    @Transactional(readOnly = true)
    public long countReconciliation() {
        return workflowRepository.countByState(ReservationCreationState.RECONCILIATION_REQUIRED);
    }

    private ReservationCreationPreparation prepareExisting(
            ReservationCreationRequest request, String fingerprint, UUID owner) {
        if (!request.getFingerprint().equals(fingerprint)) {
            return preparation(request, ReservationCreationPreparation.Type.MISMATCH, null);
        }
        if (request.getState() == ReservationCreationState.COMPLETED) {
            return preparation(request, ReservationCreationPreparation.Type.REPLAY, null);
        }
        if (request.getState() == ReservationCreationState.RECONCILIATION_REQUIRED) {
            return preparation(request, ReservationCreationPreparation.Type.RECONCILIATION, null);
        }
        int claimed = workflowRepository.claim(
                request.getIdempotencyKey(), owner, settings.leaseDuration().toMillis(), false);
        if (claimed == 0) {
            return preparation(request, ReservationCreationPreparation.Type.BUSY, null);
        }
        return executable(request.getIdempotencyKey(), owner);
    }

    private int insert(
            UUID key, String fingerprint, ReservationCreationCommand command, UUID owner, DatabaseTimeSnapshot time) {
        return workflowRepository.insertIfAbsent(
                key,
                fingerprint,
                serialize(command),
                time.utcDate(),
                owner,
                time.observedAt(),
                settings.leaseDuration().toMillis());
    }

    private ReservationCreationPreparation executable(UUID key, UUID owner) {
        ReservationCreationRequest request = workflowRepository.findById(key).orElseThrow();
        return preparation(request, ReservationCreationPreparation.Type.EXECUTE, owner);
    }

    private ReservationCreationPreparation replayable(UUID key) {
        ReservationCreationRequest request = workflowRepository.findById(key).orElseThrow();
        return preparation(request, ReservationCreationPreparation.Type.REPLAY, null);
    }

    private ReservationCreationPreparation preparation(
            ReservationCreationRequest request, ReservationCreationPreparation.Type type, UUID owner) {
        return new ReservationCreationPreparation(
                type,
                request.getIdempotencyKey(),
                owner,
                request.getState(),
                request.getRequestPayload(),
                request.getOutcome(),
                request.getExpiresAt(),
                request.getAttemptCount());
    }

    private ReservationCreationRequest owned(UUID key, UUID owner, ReservationCreationState expectedState) {
        ReservationCreationRequest request =
                workflowRepository.findLockedByIdempotencyKey(key).orElseThrow();
        if (request.getState() != expectedState || !owner.equals(request.getLeaseOwner())) {
            throw new ReservationCreationLeaseLostException();
        }
        return request;
    }

    private List<ReservationCreationFailure> activeFailures(ReservationCreationRequest request) {
        ReservationCreationCommand command = request.getRequestPayload();
        Set<String> serialNumbers = new LinkedHashSet<>();
        for (ReservationCreationCommand.Item item : command.items()) {
            serialNumbers.add(item.serialNumber());
        }
        List<Reservation> activeReservations =
                reservationRepository.findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(
                        serialNumbers, ACTIVE_STATUSES, request.getAcceptedDate());
        Set<String> active = new LinkedHashSet<>();
        for (Reservation reservation : activeReservations) {
            active.add(reservation.getSerialNumber());
        }
        List<ReservationCreationFailure> failures = new ArrayList<>();
        for (int index = 0; index < command.items().size(); index++) {
            String serialNumber = command.items().get(index).serialNumber();
            if (active.contains(serialNumber)) {
                failures.add(new ReservationCreationFailure(
                        index,
                        serialNumber,
                        "ACTIVE_RESERVATION_EXISTS",
                        "An active reservation already exists for this item."));
            }
        }
        return List.copyOf(failures);
    }

    private void completeOwned(UUID key, UUID owner, ReservationCreationOutcome outcome) {
        requireUpdated(workflowRepository.complete(key, owner, serialize(outcome), outcome.status()));
    }

    private void reconcileOwned(UUID key, UUID owner, ReservationCreationOutcome outcome) {
        requireUpdated(workflowRepository.reconcile(key, owner, serialize(outcome)));
    }

    private boolean isLogicallyExpired(ReservationCreationRequest request, Instant observedAt) {
        return request.getState() == ReservationCreationState.COMPLETED
                && !request.getExpiresAt().isAfter(observedAt);
    }

    private void validateAcceptedDate(ReservationCreationCommand command, LocalDate acceptedDate) {
        List<ReservationCommandViolation> violations =
                ReservationCreationValidation.againstAcceptedDate(command, acceptedDate);
        if (!violations.isEmpty()) {
            throw new ReservationCreationValidationException(violations);
        }
    }

    private String serialize(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ReservationCreationLeaseLostException();
        }
    }
}
