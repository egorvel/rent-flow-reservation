package com.rentflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.ReservationBacklog;
import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationCreationRequestId;

public interface ReservationCreationRequestRepository
        extends JpaRepository<ReservationCreationRequest, ReservationCreationRequestId> {
    @Query(value = "SELECT pg_try_advisory_xact_lock(:lockId)", nativeQuery = true)
    boolean tryExecutionLock(long lockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationCreationRequest> findForUpdateById(ReservationCreationRequestId id);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Query(value = "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date", nativeQuery = true)
    LocalDate databaseUtcDate();

    @Modifying
    @Query(value = """
            WITH expired AS (
                SELECT caller_scope, idempotency_key
                FROM reservation.reservation_creation_requests
                WHERE expires_at <= statement_timestamp()
                ORDER BY expires_at, caller_scope, idempotency_key
                LIMIT 1000
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM reservation.reservation_creation_requests request
            USING expired
            WHERE request.caller_scope = expired.caller_scope
              AND request.idempotency_key = expired.idempotency_key
            """, nativeQuery = true)
    int deleteExpiredChunk();

    @Query(value = """
            SELECT count(*)
            FROM reservation.reservation_creation_requests
            WHERE expires_at <= statement_timestamp()
            """, nativeQuery = true)
    long countExpired();

    @Query(value = """
            WITH measured AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT count(entry.idempotency_key) AS backlog,
                   0 AS "oldestAge",
                   EXTRACT(EPOCH FROM measured.at) AS "measuredAt"
            FROM measured LEFT JOIN reservation.reservation_creation_requests entry ON entry.expires_at <= measured.at
            GROUP BY measured.at
            """, nativeQuery = true)
    ReservationBacklog monitoringSnapshot();

    @Query(value = "SELECT set_config('statement_timeout', :milliseconds, true)", nativeQuery = true)
    String configureMonitoringTimeout(String milliseconds);
}
