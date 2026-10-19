package com.rentflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway.ClaimResult;
import com.rentflow.support.ReservationPropertiesFixture;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationCreationServiceTest {
    private final ReservationCreationRequestRepository requests = mock(ReservationCreationRequestRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final ReservationCreationCommand command = new ReservationCreationCommand(
            "customer",
            "order",
            List.of(new ReservationCreationCommand.Item(
                    "TEST-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3))));
    private ReservationCreationService service;

    @BeforeEach
    void setUp() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(metrics));
        service = new ReservationCreationService(
                requests, reservations, inventory, metrics, observations, ReservationPropertiesFixture.defaults());
        when(requests.tryExecutionLock(anyLong())).thenReturn(true);
        when(requests.databaseTime()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(requests.databaseUtcDate()).thenReturn(LocalDate.of(2026, 10, 1));
        when(inventory.claim(any(), any())).thenReturn(ClaimResult.claimed());
        when(reservations.saveAllAndFlush(any())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        metrics.close();
    }

    @ParameterizedTest
    @CsvSource({
        "CLAIMED,created",
        "MISSING,inventory_missing",
        "UNAVAILABLE,inventory_unavailable",
        "INVALID_REFERENCE,invalid_reference",
        "KEY_REUSED,key_reused"
    })
    void countsTerminalOutcomesOnlyAfterCommit(String result, String outcome) {
        when(inventory.claim(any(), any()))
                .thenReturn(ClaimResult.terminal(ClaimResult.Type.valueOf(result), List.of()));
        service.create(UUID.randomUUID(), command);
        assertThat(metrics.find("reservation.creation.committed").counter()).isNull();
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(metrics.get("reservation.creation.committed")
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isOne();
        assertThat(metrics.get("reservation.creation")
                        .tag("outcome", outcome)
                        .tag("replayed", "false")
                        .timer()
                        .count())
                .isOne();
        assertThat(metrics.get("reservation.creation.batch.size").summary().totalAmount())
                .isOne();
    }

    @Test
    void rollbackDoesNotCountACommittedOutcome() {
        service.create(UUID.randomUUID(), command);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(metrics.find("reservation.creation.committed").counter()).isNull();
        assertThat(metrics.get("reservation.creation.idempotency")
                        .tag("outcome", "completion")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void busyAndReplaySkipCommitCallbacksAndRestoreCallerScope() {
        UUID key = UUID.randomUUID();
        ReservationCreationRequest stored = new ReservationCreationRequest(key);
        stored.complete(
                IdempotencyFingerprint.of(command),
                ReservationCreationOutcomes.success(List.of()),
                requests.databaseTime());
        when(requests.findForUpdateByIdempotencyKey(key)).thenReturn(java.util.Optional.of(stored));
        Observation parent = Observation.start("caller", observations);
        try (Observation.Scope ignored = parent.openScope()) {
            assertThat(service.create(key, command).replayed()).isTrue();
            when(requests.tryExecutionLock(anyLong())).thenReturn(false);
            assertThat(service.create(key, command).code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            assertThat(observations.getCurrentObservation()).isSameAs(parent);
        } finally {
            parent.stop();
        }
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(metrics.get("reservation.creation.batch.size").summary().count())
                .isEqualTo(2);
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void technicalFailuresRecordErrorsAndDoNotLeakScopesOrRegisterCommit() {
        IllegalStateException failure = new IllegalStateException("database failure");
        when(requests.databaseTime()).thenThrow(failure);
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command)).isSameAs(failure);
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(metrics.get("reservation.creation")
                        .tag("outcome", "error")
                        .timer()
                        .count())
                .isOne();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void validationAndActiveConflictCommitTheirOwnBoundedOutcomes() {
        ReservationCreationCommand invalid = new ReservationCreationCommand(
                "customer",
                "order",
                List.of(new ReservationCreationCommand.Item(
                        "TEST-001", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))));
        assertThat(service.create(UUID.randomUUID(), invalid).code()).isEqualTo("VALIDATION_FAILED");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(metrics.get("reservation.creation.committed")
                        .tag("outcome", "validation_failed")
                        .counter()
                        .count())
                .isOne();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        when(reservations.findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of(new com.rentflow.model.Reservation(
                        "TEST-001", "customer", "order", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3))));
        assertThat(service.create(UUID.randomUUID(), command).code()).isEqualTo("ACTIVE_RESERVATION_EXISTS");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(metrics.get("reservation.creation.committed")
                        .tag("outcome", "active_conflict")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void committedMetricFailureDoesNotEscapeItsTransactionCallback() {
        SimpleMeterRegistry failing = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                if (id.getName().equals("reservation.creation.committed")) {
                    throw new IllegalStateException("counter unavailable");
                }
                return super.newCounter(id);
            }
        };
        ReservationCreationService observed = new ReservationCreationService(
                requests, reservations, inventory, failing, observations, ReservationPropertiesFixture.defaults());
        assertThat(observed.create(UUID.randomUUID(), command).status()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
        failing.close();
    }
}
