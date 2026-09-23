package com.rentflow.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations", schema = "reservation")
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "serial_number", nullable = false, length = 64)
    private String serialNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "hold_expires_at", nullable = false, updatable = false)
    private Instant holdExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.HELD;

    protected Reservation() {}

    public Reservation(String serialNumber, String customerId, String orderId, LocalDate startDate, LocalDate endDate) {
        this(
                serialNumber,
                customerId,
                orderId,
                startDate,
                endDate,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private Reservation(
            String serialNumber,
            String customerId,
            String orderId,
            LocalDate startDate,
            LocalDate endDate,
            Instant timestamp) {
        this(serialNumber, customerId, orderId, startDate, endDate, timestamp, timestamp.plusSeconds(600));
    }

    public Reservation(
            String serialNumber,
            String customerId,
            String orderId,
            LocalDate startDate,
            LocalDate endDate,
            Instant timestamp,
            Instant holdExpiresAt) {
        this.serialNumber = Objects.requireNonNull(serialNumber);
        this.customerId = Objects.requireNonNull(customerId);
        this.orderId = Objects.requireNonNull(orderId);
        this.startDate = Objects.requireNonNull(startDate);
        this.endDate = Objects.requireNonNull(endDate);
        this.timestamp = Objects.requireNonNull(timestamp);
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getOrderId() {
        return orderId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void changeStatus(ReservationStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public void replaceDetails(Reservation replacement) {
        serialNumber = replacement.serialNumber;
        customerId = replacement.customerId;
        orderId = replacement.orderId;
        startDate = replacement.startDate;
        endDate = replacement.endDate;
        status = replacement.status;
    }
}
