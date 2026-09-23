package com.rentflow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationHoldExpirationIT extends PostgresIntegrationTest {
    private static final long EXPIRATION_LOCK_ID = -6421287447981818390L;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private ReservationCreationRequestRepository creationRequests;

    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private ReservationCancellationService cancellation;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearDatabase() {
        outboxes.deleteAllInBatch();
        creationRequests.deleteAllInBatch();
        reservations.deleteAllInBatch();
    }

    @Test
    void expiresOnlyTheOldestDueHeldReservationPerTransaction() {
        Instant databaseTime = reservations.databaseTime();
        Reservation oldest = reservations.saveAndFlush(
                reservation("EXPIRE-OLDEST", ReservationStatus.HELD, databaseTime.minusSeconds(120)));
        Reservation next = reservations.saveAndFlush(
                reservation("EXPIRE-NEXT", ReservationStatus.HELD, databaseTime.minusSeconds(60)));
        Reservation future = reservations.saveAndFlush(
                reservation("EXPIRE-FUTURE", ReservationStatus.HELD, databaseTime.plusSeconds(60)));
        Reservation confirmed = reservations.saveAndFlush(
                reservation("EXPIRE-CONFIRMED", ReservationStatus.CONFIRMED, databaseTime.minusSeconds(180)));

        assertThat(cancellation.expireOldestHeld()).isEqualTo(ReservationCancellationService.ExpirationResult.EXPIRED);

        assertThat(reservations.findById(oldest.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservations.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reservations.findById(future.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reservations.findById(confirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxes.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getRecordKey()).isEqualTo("EXPIRE-OLDEST");
            assertThat(outbox.getOccurredAt()).isAfter(oldest.getHoldExpiresAt());
        });
    }

    @Test
    void returnsBusyWhileAnotherTransactionOwnsTheExpirationLock() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (ResultSet result =
                    statement.executeQuery("SELECT pg_advisory_xact_lock(" + EXPIRATION_LOCK_ID + ")")) {
                assertThat(result.next()).isTrue();
            }

            assertThat(cancellation.expireOldestHeld())
                    .isEqualTo(ReservationCancellationService.ExpirationResult.LOCK_BUSY);
            connection.rollback();
        }
    }

    @Test
    void manualAndTimedCancellationRaceCreatesOneEvent() throws Exception {
        Instant databaseTime = reservations.databaseTime();
        Reservation reservation = reservations.saveAndFlush(
                reservation("EXPIRE-RACE", ReservationStatus.HELD, databaseTime.minusSeconds(60)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                cancellation.cancel(reservation.getId());
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                cancellation.expireOldestHeld();
                return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(outboxes.count()).isOne();
    }

    private Reservation reservation(String serialNumber, ReservationStatus status, Instant holdExpiresAt) {
        Reservation reservation = new Reservation(
                serialNumber,
                "CUSTOMER-001",
                "ORDER-001",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 3),
                holdExpiresAt.minus(Duration.ofMinutes(10)),
                holdExpiresAt);
        reservation.changeStatus(status);
        return reservation;
    }
}
