package com.rentflow.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_creation_requests", schema = "reservation")
public class ReservationCreationRequest {
    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private ReservationCreationCommand requestPayload;

    @Column(name = "accepted_date", nullable = false)
    private LocalDate acceptedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationCreationState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ReservationCreationOutcome outcome;

    @Column(name = "lease_owner")
    private UUID leaseOwner;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "recovery_deadline", nullable = false, updatable = false)
    private Instant recoveryDeadline;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ReservationCreationRequest() {}

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public ReservationCreationCommand getRequestPayload() {
        return requestPayload;
    }

    public LocalDate getAcceptedDate() {
        return acceptedDate;
    }

    public ReservationCreationState getState() {
        return state;
    }

    public ReservationCreationOutcome getOutcome() {
        return outcome;
    }

    public UUID getLeaseOwner() {
        return leaseOwner;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getRecoveryDeadline() {
        return recoveryDeadline;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
