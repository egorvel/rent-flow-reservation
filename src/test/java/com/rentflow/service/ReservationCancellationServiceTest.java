package com.rentflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.support.ReservationPropertiesFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCancellationServiceTest {
    private static final Instant DATABASE_TIME = Instant.parse("2026-09-21T15:30:00Z");

    private ReservationRepository reservations;
    private ReservationCancellationOutboxRepository outboxes;
    private ObjectMapper mapper;
    private ReservationCancellationService service;

    @BeforeEach
    void setUp() {
        reservations = mock(ReservationRepository.class);
        outboxes = mock(ReservationCancellationOutboxRepository.class);
        mapper = new ObjectMapper();
        service = new ReservationCancellationService(
                reservations, outboxes, mapper, ReservationPropertiesFixture.runtimeDefaults());
        when(outboxes.databaseTime()).thenReturn(DATABASE_TIME);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"HELD", "CONFIRMED"})
    void cancelsEligibleReservationAndPersistsTheExactEvent(ReservationStatus initialStatus) throws Exception {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = reservation(initialStatus);
        when(reservations.findForUpdateById(reservationId)).thenReturn(Optional.of(reservation));

        service.cancel(reservationId);

        ArgumentCaptor<ReservationCancellationOutbox> captor =
                ArgumentCaptor.forClass(ReservationCancellationOutbox.class);
        verify(outboxes).save(captor.capture());
        ReservationCancellationOutbox outbox = captor.getValue();
        JsonNode payload = mapper.readTree(outbox.getPayload());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(outbox.getRecordKey()).isEqualTo("Drill-001");
        assertThat(outbox.getOccurredAt()).isEqualTo(DATABASE_TIME);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(DATABASE_TIME);
        assertThat(outbox.getEventId().version()).isEqualTo(4);
        assertThat(outbox.getPayload())
                .isEqualTo(
                        "{\"eventId\":\"%s\",\"eventType\":\"ReservationCancelled\",\"eventVersion\":1,\"occurredAt\":\"2026-09-21T15:30:00Z\",\"serialNumber\":\"Drill-001\"}"
                                .formatted(outbox.getEventId()));
        assertThat(payload.propertyNames())
                .containsExactly("eventId", "eventType", "eventVersion", "occurredAt", "serialNumber");
        assertThat(payload.has("reservationId")).isFalse();
    }

    @Test
    void alreadyCancelledIsANoOpWithoutSamplingTime() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = reservation(ReservationStatus.CANCELLED);
        when(reservations.findForUpdateById(reservationId)).thenReturn(Optional.of(reservation));

        service.cancel(reservationId);

        verify(outboxes, never()).databaseTime();
        verify(outboxes, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void missingReservationFailsWithoutAnOutboxWrite() {
        UUID reservationId = UUID.randomUUID();
        when(reservations.findForUpdateById(reservationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(reservationId)).isInstanceOf(ReservationNotFoundException.class);

        verify(outboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oversizedPayloadFailsBeforeChangingTheReservation() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = reservation(ReservationStatus.HELD);
        when(reservations.findForUpdateById(reservationId)).thenReturn(Optional.of(reservation));
        service = new ReservationCancellationService(
                reservations, outboxes, mapper, ReservationPropertiesFixture.runtimeWithMaxPayloadBytes(32));

        assertThatThrownBy(() -> service.cancel(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("size limit");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        verify(outboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expirationStopsWhenAnotherInstanceOwnsTheClusterLock() {
        when(reservations.tryExpirationLock()).thenReturn(false);

        assertThat(service.expireOldestHeld()).isEqualTo(ReservationCancellationService.ExpirationResult.LOCK_BUSY);

        verify(reservations, never()).databaseTime();
        verify(outboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expirationReturnsEmptyWhenNoHeldDeadlineIsDue() {
        when(reservations.tryExpirationLock()).thenReturn(true);
        when(reservations.databaseTime()).thenReturn(DATABASE_TIME);
        when(reservations.findFirstByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAscIdAsc(
                        ReservationStatus.HELD, DATABASE_TIME))
                .thenReturn(Optional.empty());

        assertThat(service.expireOldestHeld()).isEqualTo(ReservationCancellationService.ExpirationResult.EMPTY);

        verify(outboxes, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expirationUsesTheSharedCancellationTransitionAtDatabaseTime() {
        Reservation reservation = reservation(ReservationStatus.HELD);
        when(reservations.tryExpirationLock()).thenReturn(true);
        when(reservations.databaseTime()).thenReturn(DATABASE_TIME);
        when(reservations.findFirstByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAscIdAsc(
                        ReservationStatus.HELD, DATABASE_TIME))
                .thenReturn(Optional.of(reservation));

        assertThat(service.expireOldestHeld()).isEqualTo(ReservationCancellationService.ExpirationResult.EXPIRED);

        ArgumentCaptor<ReservationCancellationOutbox> captor =
                ArgumentCaptor.forClass(ReservationCancellationOutbox.class);
        verify(outboxes).save(captor.capture());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(captor.getValue().getOccurredAt()).isEqualTo(DATABASE_TIME);
        assertThat(captor.getValue().getPayload()).contains("\"eventType\":\"ReservationCancelled\"");
    }

    private Reservation reservation(ReservationStatus status) {
        Reservation reservation = new Reservation(
                "Drill-001", "CUSTOMER-001", "ORDER-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        reservation.changeStatus(status);
        return reservation;
    }
}
