package com.rentflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.ReservationCreationRequest;

public interface ReservationCreationRequestRepository extends JpaRepository<ReservationCreationRequest, UUID> {
    @Query(value = "SELECT pg_try_advisory_xact_lock(:lockId)", nativeQuery = true)
    boolean tryExecutionLock(long lockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationCreationRequest> findForUpdateByIdempotencyKey(UUID idempotencyKey);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Query(value = "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date", nativeQuery = true)
    LocalDate databaseUtcDate();

    @Modifying
    @Query(value = """
            WITH expired AS (
                SELECT idempotency_key
                FROM reservation.reservation_creation_requests
                WHERE expires_at <= statement_timestamp()
                ORDER BY expires_at, idempotency_key
                LIMIT 1000
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM reservation.reservation_creation_requests request
            USING expired
            WHERE request.idempotency_key = expired.idempotency_key
            """, nativeQuery = true)
    int deleteExpiredChunk();

    @Query(value = """
            SELECT count(*)
            FROM reservation.reservation_creation_requests
            WHERE expires_at <= statement_timestamp()
            """, nativeQuery = true)
    long countExpired();
}
