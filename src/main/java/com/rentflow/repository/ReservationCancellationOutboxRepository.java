package com.rentflow.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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
}
