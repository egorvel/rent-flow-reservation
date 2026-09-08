package com.rentflow.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationCreationState;

public interface ReservationCreationRequestRepository extends JpaRepository<ReservationCreationRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationCreationRequest> findLockedByIdempotencyKey(UUID key);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    INSERT INTO reservation.reservation_creation_requests (
                        idempotency_key, fingerprint, request_payload, accepted_date, state,
                        lease_owner, lease_expires_at, next_attempt_at, attempt_count,
                        recorded_at, updated_at, recovery_deadline
                    ) VALUES (
                        :key, :fingerprint, CAST(:requestPayload AS jsonb), :acceptedDate,
                        'PENDING_LOCAL_CHECK', :owner,
                        CAST(:recordedAt AS timestamptz) + CAST(:leaseMilliseconds AS bigint) * INTERVAL '1 millisecond',
                        :recordedAt, 0, :recordedAt, :recordedAt,
                        CAST(:recordedAt AS timestamptz) + INTERVAL '168 hours'
                    )
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") UUID key,
            @Param("fingerprint") String fingerprint,
            @Param("requestPayload") String requestPayload,
            @Param("acceptedDate") LocalDate acceptedDate,
            @Param("owner") UUID owner,
            @Param("recordedAt") Instant recordedAt,
            @Param("leaseMilliseconds") long leaseMilliseconds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    WITH time_sample AS (SELECT statement_timestamp() AS observed_at)
                    UPDATE reservation.reservation_creation_requests request
                    SET lease_owner = :owner,
                        lease_expires_at = time_sample.observed_at
                            + CAST(:leaseMilliseconds AS bigint) * INTERVAL '1 millisecond',
                        updated_at = time_sample.observed_at
                    FROM time_sample
                    WHERE request.idempotency_key = :key
                      AND request.state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                      AND (NOT :onlyWhenDue OR request.next_attempt_at <= time_sample.observed_at)
                      AND (request.lease_expires_at IS NULL OR request.lease_expires_at <= time_sample.observed_at)
                    """, nativeQuery = true)
    int claim(
            @Param("key") UUID key,
            @Param("owner") UUID owner,
            @Param("leaseMilliseconds") long leaseMilliseconds,
            @Param("onlyWhenDue") boolean onlyWhenDue);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE reservation.reservation_creation_requests
                    SET state = 'PENDING_INVENTORY', updated_at = statement_timestamp()
                    WHERE idempotency_key = :key
                      AND state = 'PENDING_LOCAL_CHECK'
                      AND lease_owner = :owner
                    """, nativeQuery = true)
    int advanceToInventory(@Param("key") UUID key, @Param("owner") UUID owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE reservation.reservation_creation_requests
                    SET attempt_count = attempt_count + 1, updated_at = statement_timestamp()
                    WHERE idempotency_key = :key
                      AND state = 'PENDING_INVENTORY'
                      AND lease_owner = :owner
                    """, nativeQuery = true)
    int incrementAttempt(@Param("key") UUID key, @Param("owner") UUID owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    WITH time_sample AS (SELECT statement_timestamp() AS observed_at)
                    UPDATE reservation.reservation_creation_requests request
                    SET state = 'COMPLETED', outcome = CAST(:outcome AS jsonb),
                        terminal_http_status = :status, lease_owner = NULL, lease_expires_at = NULL,
                        updated_at = time_sample.observed_at, completed_at = time_sample.observed_at,
                        expires_at = time_sample.observed_at + INTERVAL '168 hours'
                    FROM time_sample
                    WHERE request.idempotency_key = :key
                      AND request.state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                      AND request.lease_owner = :owner
                    """, nativeQuery = true)
    int complete(
            @Param("key") UUID key,
            @Param("owner") UUID owner,
            @Param("outcome") String outcome,
            @Param("status") int status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE reservation.reservation_creation_requests
                    SET state = 'RECONCILIATION_REQUIRED', outcome = CAST(:outcome AS jsonb),
                        terminal_http_status = NULL, lease_owner = NULL, lease_expires_at = NULL,
                        updated_at = statement_timestamp()
                    WHERE idempotency_key = :key
                      AND state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                      AND lease_owner = :owner
                    """, nativeQuery = true)
    int reconcile(@Param("key") UUID key, @Param("owner") UUID owner, @Param("outcome") String outcome);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    WITH time_sample AS (SELECT statement_timestamp() AS observed_at)
                    UPDATE reservation.reservation_creation_requests request
                    SET lease_owner = NULL, lease_expires_at = NULL,
                        next_attempt_at = time_sample.observed_at
                            + CAST(:delayMilliseconds AS bigint) * INTERVAL '1 millisecond',
                        updated_at = time_sample.observed_at
                    FROM time_sample
                    WHERE request.idempotency_key = :key
                      AND request.state = 'PENDING_INVENTORY'
                      AND request.lease_owner = :owner
                    """, nativeQuery = true)
    int releaseForRetry(
            @Param("key") UUID key, @Param("owner") UUID owner, @Param("delayMilliseconds") long delayMilliseconds);

    @Query(value = """
                    SELECT idempotency_key
                    FROM reservation.reservation_creation_requests
                    WHERE state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                      AND next_attempt_at <= statement_timestamp()
                      AND (lease_expires_at IS NULL OR lease_expires_at <= statement_timestamp())
                    ORDER BY next_attempt_at, recorded_at, idempotency_key
                    """, nativeQuery = true)
    List<UUID> findDueKeys(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    DELETE FROM reservation.reservation_creation_requests
                    WHERE idempotency_key IN (
                        SELECT idempotency_key
                        FROM reservation.reservation_creation_requests
                        WHERE state = 'COMPLETED' AND expires_at <= statement_timestamp()
                        ORDER BY expires_at, idempotency_key
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                    )
                    """, nativeQuery = true)
    int deleteExpiredCompleted(@Param("limit") int limit);

    long countByState(ReservationCreationState state);

    @Query(value = """
                    SELECT count(*) FROM reservation.reservation_creation_requests
                    WHERE state IN ('PENDING_LOCAL_CHECK', 'PENDING_INVENTORY')
                      AND next_attempt_at <= statement_timestamp()
                      AND (lease_expires_at IS NULL OR lease_expires_at <= statement_timestamp())
                    """, nativeQuery = true)
    long countDuePending();

    @Query(value = """
                    SELECT count(*) FROM reservation.reservation_creation_requests
                    WHERE state = 'COMPLETED' AND expires_at <= statement_timestamp()
                    """, nativeQuery = true)
    long countExpiredCompleted();
}
