package com.rentflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findForUpdateById(UUID id);

    @Query(value = "SELECT pg_try_advisory_xact_lock(-6421287447981818390)", nativeQuery = true)
    boolean tryExpirationLock();

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findFirstByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAscIdAsc(
            ReservationStatus status, Instant databaseTime);

    long countByStatusAndHoldExpiresAtLessThanEqual(ReservationStatus status, Instant databaseTime);

    @Query("""
            SELECT min(reservation.holdExpiresAt)
            FROM Reservation reservation
            WHERE reservation.status = :status
              AND reservation.holdExpiresAt <= :databaseTime
            """)
    Optional<Instant> oldestOverdueHoldExpiresAt(ReservationStatus status, Instant databaseTime);

    List<Reservation> findAllBySerialNumberInAndStatusInAndEndDateGreaterThanEqual(
            Collection<String> serialNumbers, Collection<ReservationStatus> statuses, LocalDate acceptedDate);
}
