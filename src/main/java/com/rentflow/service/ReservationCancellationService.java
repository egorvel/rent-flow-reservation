package com.rentflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationStatus;
import com.rentflow.properties.ReservationProperties;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationCancellationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCancellationService.class);
    private static final String EVENT_TYPE = "ReservationCancelled";
    private static final int EVENT_VERSION = 1;

    private final ReservationRepository reservations;
    private final ReservationCancellationOutboxRepository outboxes;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;
    private final Tracer tracer;
    private final Propagator propagator;

    public ReservationCancellationService(
            ReservationRepository reservations,
            ReservationCancellationOutboxRepository outboxes,
            ObjectMapper mapper,
            ReservationProperties properties,
            Tracer tracer,
            Propagator propagator) {
        this.reservations = reservations;
        this.outboxes = outboxes;
        this.mapper = mapper;
        this.maxPayloadBytes = properties.cancellation().maxPayloadBytes();
        this.tracer = tracer;
        this.propagator = propagator;
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
        transitionToCancelled(reservation, occurredAt, "manual");
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
        transitionToCancelled(reservation, databaseTime, "expiration");
        return ExpirationResult.EXPIRED;
    }

    private void transitionToCancelled(Reservation reservation, Instant occurredAt, String source) {
        UUID eventId = UUID.randomUUID();
        ReservationCancellationEvent event = new ReservationCancellationEvent(
                eventId.toString(), EVENT_TYPE, EVENT_VERSION, occurredAt.toString(), reservation.getSerialNumber());
        String payload = serialize(event);

        reservation.changeStatus(ReservationStatus.CANCELLED);
        Map<String, String> context = originContext();
        outboxes.save(new ReservationCancellationOutbox(
                eventId,
                reservation.getSerialNumber(),
                payload,
                occurredAt,
                context.get("traceparent"),
                context.get("tracestate")));
        logCancellationAfterCommit(reservation, eventId, source);
    }

    private void logCancellationAfterCommit(Reservation reservation, UUID eventId, String source) {
        Span origin = tracer.currentSpan();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    logCommittedCancellation(reservation, eventId, source, origin);
                }
            });
        }
    }

    private void logCommittedCancellation(Reservation reservation, UUID eventId, String source, Span origin) {
        try (Tracer.SpanInScope _ = tracer.withSpan(origin)) {
            org.slf4j.spi.LoggingEventBuilder log = LOGGER.atInfo()
                    .addKeyValue("eventId", eventId)
                    .addKeyValue("reservationId", reservation.getId())
                    .addKeyValue("serialNumber", reservation.getSerialNumber())
                    .addKeyValue("source", source)
                    .addKeyValue("outcome", "cancelled");
            if (origin != null) {
                log.addKeyValue("originTraceId", origin.context().traceId())
                        .addKeyValue("originSpanId", origin.context().spanId());
            }
            log.log("Reservation cancellation committed.");
        } catch (RuntimeException failure) {
            LOGGER.warn("Committed cancellation telemetry could not be recorded.", failure);
        }
    }

    private Map<String, String> originContext() {
        try {
            Span span = tracer.currentSpan();
            if (span == null) {
                return Map.of();
            }
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(span.context(), carrier, Map::put);
            String parent = carrier.get("traceparent");
            String state = carrier.get("tracestate");
            if (parent == null || parent.length() > 55 || state != null && state.length() > 512) {
                return Map.of();
            }
            return carrier;
        } catch (RuntimeException failure) {
            LOGGER.warn("Cancellation origin context could not be captured.", failure);
            return Map.of();
        }
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

    private record ReservationCancellationEvent(
            String eventId, String eventType, int eventVersion, String occurredAt, String serialNumber) {}
}
