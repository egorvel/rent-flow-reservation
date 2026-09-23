package com.rentflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationEvent;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationCancellationService {
    private static final String EVENT_TYPE = "ReservationCancelled";
    private static final int EVENT_VERSION = 1;

    private final ReservationRepository reservations;
    private final ReservationCancellationOutboxRepository outboxes;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;

    public ReservationCancellationService(
            ReservationRepository reservations,
            ReservationCancellationOutboxRepository outboxes,
            ObjectMapper mapper,
            @Value("${reservation.cancellation.max-payload-bytes:4096}") int maxPayloadBytes) {
        this.reservations = reservations;
        this.outboxes = outboxes;
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Transactional
    public void cancel(UUID reservationId) {
        Reservation reservation = reservations
                .findForUpdateById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return;
        }

        Instant occurredAt = outboxes.databaseTime();
        transitionToCancelled(reservation, occurredAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExpirationResult expireOldestHeld() {
        if (!reservations.tryExpirationLock()) {
            return ExpirationResult.LOCK_BUSY;
        }
        Instant databaseTime = reservations.databaseTime();
        Reservation reservation = reservations
                .findFirstByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAscIdAsc(
                        ReservationStatus.HELD, databaseTime)
                .orElse(null);
        if (reservation == null) {
            return ExpirationResult.EMPTY;
        }
        transitionToCancelled(reservation, databaseTime);
        return ExpirationResult.EXPIRED;
    }

    private void transitionToCancelled(Reservation reservation, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        ReservationCancellationEvent event = new ReservationCancellationEvent(
                eventId.toString(), EVENT_TYPE, EVENT_VERSION, occurredAt.toString(), reservation.getSerialNumber());
        String payload = serialize(event);

        reservation.changeStatus(ReservationStatus.CANCELLED);
        outboxes.save(new ReservationCancellationOutbox(eventId, reservation.getSerialNumber(), payload, occurredAt));
    }

    private String serialize(ReservationCancellationEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize reservation cancellation event", exception);
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new IllegalStateException("Reservation cancellation event exceeds the configured size limit");
        }
        return payload;
    }

    public enum ExpirationResult {
        EXPIRED,
        EMPTY,
        LOCK_BUSY
    }
}
