package com.rentflow.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.rentflow.model.ReservationBacklog;
import com.rentflow.model.ReservationCancellationOutbox;

public interface ReservationCancellationOutboxRepository extends JpaRepository<ReservationCancellationOutbox, UUID> {
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Query(value = "SELECT pg_try_advisory_xact_lock(-6421287447981818391)", nativeQuery = true)
    boolean tryRelayLock();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationCancellationOutbox> findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc();

    long countByPublishedAtIsNull();

    @Query("""
            SELECT min(outbox.occurredAt)
            FROM ReservationCancellationOutbox outbox
            WHERE outbox.publishedAt IS NULL
            """)
    Optional<Instant> oldestPendingOccurredAt();

    @Modifying
    @Query(value = """
            WITH expired AS (
                SELECT event_id
                FROM reservation.reservation_cancellation_outbox
                WHERE published_at <= clock_timestamp() - INTERVAL '30 days'
                ORDER BY published_at, event_id
                LIMIT 1000
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM reservation.reservation_cancellation_outbox outbox
            USING expired
            WHERE outbox.event_id = expired.event_id
            """, nativeQuery = true)
    int deletePublishedChunk();

    @Query(value = """
            SELECT count(*)
            FROM reservation.reservation_cancellation_outbox
            WHERE published_at <= clock_timestamp() - INTERVAL '30 days'
            """, nativeQuery = true)
    long countCleanupBacklog();

    @Query(value = """
            WITH measured AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT count(entry.event_id) AS backlog,
                   COALESCE(EXTRACT(EPOCH FROM measured.at - min(entry.occurred_at)), 0) AS "oldestAge",
                   EXTRACT(EPOCH FROM measured.at) AS "measuredAt"
            FROM measured LEFT JOIN reservation.reservation_cancellation_outbox entry ON entry.published_at IS NULL
            GROUP BY measured.at
            """, nativeQuery = true)
    ReservationBacklog monitoringSnapshot();

    @Query(value = """
            WITH measured AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT count(entry.event_id) AS backlog,
                   0 AS "oldestAge",
                   EXTRACT(EPOCH FROM measured.at) AS "measuredAt"
            FROM measured LEFT JOIN reservation.reservation_cancellation_outbox entry ON entry.published_at <= measured.at - INTERVAL '30 days'
            GROUP BY measured.at
            """, nativeQuery = true)
    ReservationBacklog cleanupMonitoringSnapshot();
}
