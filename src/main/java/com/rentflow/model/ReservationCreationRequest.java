package com.rentflow.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_creation_requests", schema = "reservation")
public class ReservationCreationRequest {
    @Id
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private ReservationCreationOutcome outcome;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ReservationCreationRequest() {}

    public ReservationCreationRequest(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void complete(String fingerprint, ReservationCreationOutcome outcome, Instant databaseTime) {
        this.fingerprint = fingerprint;
        this.outcome = outcome;
        this.httpStatus = outcome.status();
        this.recordedAt = databaseTime;
        this.expiresAt = databaseTime.plus(Duration.ofDays(7));
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public ReservationCreationOutcome getOutcome() {
        return outcome;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
