package com.rentflow.model;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {
    @Test
    void persistenceAssignsHeldAndMicrosecondCreationTime() {
        Reservation reservation = reservation("DRILL-001");
        reservation.changeStatus(ReservationStatus.CONFIRMED);
        Instant before = Instant.now().minusMillis(1);
        reservation.initializeCreation();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getTimestamp()).isBetween(before, Instant.now());
        assertThat(reservation.getTimestamp().getNano() % 1000).isZero();
    }

    @Test
    void replacementPreservesCreationTimeAndChangesEveryMutableField() {
        Reservation original = reservation("DRILL-001");
        original.initializeCreation();
        Instant timestamp = original.getTimestamp();
        Reservation replacement =
                new Reservation("SAW-002", "CUSTOMER-2", "ORDER-2", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        replacement.changeStatus(ReservationStatus.CANCELLED);
        original.replaceDetails(replacement);
        assertThat(original.getTimestamp()).isEqualTo(timestamp);
        assertThat(original.getSerialNumber()).isEqualTo("SAW-002");
        assertThat(original.getCustomerId()).isEqualTo("CUSTOMER-2");
        assertThat(original.getOrderId()).isEqualTo("ORDER-2");
        assertThat(original.getStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(original.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 2));
        assertThat(original.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    private Reservation reservation(String serial) {
        return new Reservation(serial, "CUSTOMER-1", "ORDER-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
    }
}
