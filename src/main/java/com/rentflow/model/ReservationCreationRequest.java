package com.rentflow.model;

import java.time.Duration;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_creation_requests", schema = "reservation")
public class ReservationCreationRequest {
    @EmbeddedId
    private ReservationCreationRequestId id;

    @Column(name = "target_customer_id", nullable = false, columnDefinition = "text")
    private String targetCustomerId;

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

    public ReservationCreationRequest(ReservationCreationRequestId id) {
        this.id = id;
    }

    public void complete(
            String fingerprint, ReservationCreationOutcome outcome, Instant databaseTime, String targetCustomerId) {
        this.targetCustomerId = targetCustomerId;
        this.fingerprint = fingerprint;
        this.outcome = outcome;
        this.httpStatus = outcome.status();
        this.recordedAt = databaseTime;
        this.expiresAt = databaseTime.plus(Duration.ofDays(7));
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTargetCustomerId() {
        return targetCustomerId;
    }

    public ReservationCreationOutcome getOutcome() {
        return outcome;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
