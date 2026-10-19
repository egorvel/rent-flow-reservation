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
import com.rentflow.model.ReservationBacklog;
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

    @Query(value = """
            WITH measured AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT count(entry.id) AS backlog,
                   COALESCE(EXTRACT(EPOCH FROM measured.at - min(entry.hold_expires_at)), 0) AS "oldestAge",
                   EXTRACT(EPOCH FROM measured.at) AS "measuredAt"
            FROM measured LEFT JOIN reservation.reservations entry ON entry.status = 'HELD' AND entry.hold_expires_at <= measured.at
            GROUP BY measured.at
            """, nativeQuery = true)
    ReservationBacklog monitoringSnapshot();
}
