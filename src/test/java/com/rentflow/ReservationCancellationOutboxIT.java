package com.rentflow;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.service.ReservationCleanupService;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationCancellationOutboxIT extends PostgresIntegrationTest {
    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private ReservationCleanupService cleanup;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void clearOutbox() {
        outboxes.deleteAllInBatch();
    }

    @Test
    void selectsTheGloballyOldestUnpublishedEvent() {
        Instant now = outboxes.databaseTime();
        ReservationCancellationOutbox newer = new ReservationCancellationOutbox(
                UUID.randomUUID(), "FIFO-NEW", "{\"event\":\"new\"}", now.minusSeconds(1));
        ReservationCancellationOutbox oldest = new ReservationCancellationOutbox(
                UUID.randomUUID(), "FIFO-OLD", "{\"event\":\"old\"}", now.minusSeconds(2));
        outboxes.saveAllAndFlush(java.util.List.of(newer, oldest));

        UUID selected = new TransactionTemplate(transactions)
                .execute(status -> outboxes.findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc()
                        .orElseThrow()
                        .getEventId());

        assertThat(selected).isEqualTo(oldest.getEventId());
    }

    @Test
    void transactionAdvisoryLockExcludesAnotherInstanceAndReleasesAfterCommit() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> owner = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                boolean locked = outboxes.tryRelayLock();
                acquired.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return locked;
            }));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

            Boolean competing = new TransactionTemplate(transactions).execute(status -> outboxes.tryRelayLock());
            assertThat(competing).isFalse();
            release.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS)).isTrue();
        }

        Boolean afterCommit = new TransactionTemplate(transactions).execute(status -> outboxes.tryRelayLock());
        assertThat(afterCommit).isTrue();
    }

    @Test
    void cleanupDeletesOnlyAcknowledgedRowsOlderThanThirtyDays() {
        UUID oldPublished = insert("CLEAN-OLD", "31 days", true);
        UUID recentPublished = insert("CLEAN-RECENT", "29 days", true);
        UUID unpublished = insert("CLEAN-PENDING", "40 days", false);

        assertThat(cleanup.countCancellationCleanupBacklog()).isOne();
        assertThat(cleanup.deletePublishedCancellationChunk()).isOne();

        assertThat(outboxes.findById(oldPublished)).isEmpty();
        assertThat(outboxes.findById(recentPublished)).isPresent();
        assertThat(outboxes.findById(unpublished)).isPresent();
        assertThat(cleanup.countCancellationCleanupBacklog()).isZero();
    }

    private UUID insert(String key, String age, boolean published) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservation.reservation_cancellation_outbox (
                    event_id, record_key, payload, occurred_at, published_at,
                    attempt_count, next_attempt_at
                ) VALUES (
                    ?, ?, '{"event":true}', clock_timestamp() - ?::interval,
                    CASE WHEN ? THEN clock_timestamp() - ?::interval ELSE NULL END,
                    CASE WHEN ? THEN 1 ELSE 0 END,
                    CASE WHEN ? THEN NULL ELSE clock_timestamp() - ?::interval END
                )
                """, eventId, key, age, published, age, published, published, age);
        return eventId;
    }
}
