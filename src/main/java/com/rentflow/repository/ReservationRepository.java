package com.rentflow.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(
            Collection<String> serialNumbers, Collection<ReservationStatus> statuses, LocalDate acceptedDate);
}
