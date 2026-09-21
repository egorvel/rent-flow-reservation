package com.rentflow.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_cancellation_outbox", schema = "reservation")
public class ReservationCancellationOutbox {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "record_key", nullable = false, updatable = false, length = 64)
    private String recordKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_code", length = 32)
    private ReservationCancellationFailureCode lastFailureCode;

    protected ReservationCancellationOutbox() {}

    public ReservationCancellationOutbox(UUID eventId, String recordKey, String payload, Instant occurredAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.recordKey = Objects.requireNonNull(recordKey);
        this.payload = Objects.requireNonNull(payload);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.nextAttemptAt = occurredAt;
    }

    public void recordPublished(Instant publicationTime) {
        attemptCount = Math.incrementExact(attemptCount);
        publishedAt = Objects.requireNonNull(publicationTime);
        nextAttemptAt = null;
    }

    public void recordFailure(
            Instant failureTime, ReservationCancellationFailureCode failureCode, Instant nextAttemptTime) {
        attemptCount = Math.incrementExact(attemptCount);
        lastFailureAt = Objects.requireNonNull(failureTime);
        lastFailureCode = Objects.requireNonNull(failureCode);
        nextAttemptAt = Objects.requireNonNull(nextAttemptTime);
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getRecordKey() {
        return recordKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public ReservationCancellationFailureCode getLastFailureCode() {
        return lastFailureCode;
    }
}
