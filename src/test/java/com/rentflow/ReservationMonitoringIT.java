package com.rentflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.properties.ReservationProperties;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.ReservationMonitoringService;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "reservation.cancellation.expiration.enabled=false",
            "reservation.observability.query-timeout=200ms"
        })
@AutoConfigureMetrics
class ReservationMonitoringIT extends PostgresIntegrationTest {
    @Autowired
    private ReservationProperties properties;

    @Autowired
    private ReservationMonitoringService monitoring;

    @Autowired
    private ReservationCancellationOutboxRepository outboxes;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    @Qualifier("taskScheduler") private ThreadPoolTaskScheduler workers;

    @Autowired
    @Qualifier("reservationMonitoringTaskScheduler") private ThreadPoolTaskScheduler monitors;

    @Test
    void bindsReservationPropertiesFromConfigurationAndOverrides() {
        assertThat(properties.creation().cleanup())
                .isEqualTo(new ReservationProperties.Creation.Cleanup("0 0 3 * * *", Duration.ofSeconds(60)));
        assertThat(properties.cancellation().topic()).isEqualTo("rentflow.reservation.cancelled.v1");
        assertThat(properties.cancellation().maxPayloadBytes()).isEqualTo(4096);
        assertThat(properties.cancellation().relay())
                .isEqualTo(new ReservationProperties.Cancellation.Relay(
                        false, Duration.ofSeconds(1), Duration.ofSeconds(5), 100, Duration.ofSeconds(50)));
        assertThat(properties.cancellation().retry())
                .isEqualTo(new ReservationProperties.Cancellation.Retry(
                        Duration.ofSeconds(1), 2, Duration.ofMinutes(5), 0.2));
        assertThat(properties.cancellation().cleanup())
                .isEqualTo(
                        new ReservationProperties.Cancellation.Cleanup(false, "0 30 3 * * *", Duration.ofSeconds(60)));
        assertThat(properties.cancellation().expiration())
                .isEqualTo(new ReservationProperties.Cancellation.Expiration(
                        false, Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(5), 100));
        assertThat(properties.observability())
                .isEqualTo(new ReservationProperties.Observability(
                        Duration.ofSeconds(15), Duration.ofMinutes(1), Duration.ofMillis(200)));
    }

    @Test
    void disabledWorkersStillMeasureDatabaseBacklogsAndClockAges() {
        outboxes.deleteAllInBatch();
        reservations.deleteAllInBatch();
        Instant now = outboxes.databaseTime();
        outboxes.saveAndFlush(
                new ReservationCancellationOutbox(UUID.randomUUID(), "MONITOR-PENDING", "{}", now.minusSeconds(180)));
        reservations.saveAndFlush(new Reservation(
                "MONITOR-OVERDUE",
                "customer",
                "order",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 3),
                now.minusSeconds(900),
                now.minusSeconds(60)));
        monitoring.refreshOutbox();
        monitoring.refreshExpiration();
        monitoring.refreshCreationCleanup();
        monitoring.refreshOutboxCleanup();
        assertThat(gauge("reservation.cancellation.outbox.pending")).isOne();
        assertThat(gauge("reservation.cancellation.outbox.oldest.age")).isBetween(180.0, 190.0);
        assertThat(gauge("reservation.cancellation.expiration.overdue")).isOne();
        assertThat(gauge("reservation.cancellation.expiration.oldest.age")).isBetween(60.0, 70.0);
        assertThat(metrics.find("reservation.worker.enabled").meters()).isEmpty();
        assertThat(metrics.get("reservation.observability.backlog.last.success.timestamp")
                        .tag("backlog", "outbox")
                        .gauge()
                        .value())
                .isBetween((double) now.getEpochSecond(), (double)
                        now.plusSeconds(10).getEpochSecond());
        assertThat(gauge("reservation.cancellation.outbox.cleanup.backlog")).isZero();
    }

    @Test
    void postgresCancelsBlockedMeasurementAndRetainsTheWholePriorSnapshot() throws Exception {
        monitoring.refreshOutbox();
        double previous = gauge("reservation.cancellation.outbox.pending");
        double timestamp = metrics.get("reservation.observability.backlog.last.success.timestamp")
                .tag("backlog", "outbox")
                .gauge()
                .value();
        double failures =
                metrics
                        .find("reservation.observability.backlog.refresh.failures")
                        .tag("backlog", "outbox")
                        .counters()
                        .stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count)
                        .sum();
        try (Connection connection =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), RESERVATION_USERNAME, RESERVATION_PASSWORD)) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("LOCK TABLE reservation.reservation_cancellation_outbox IN ACCESS EXCLUSIVE MODE");
                long started = System.nanoTime();
                monitoring.refreshOutbox();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(metrics.get("reservation.observability.backlog.refresh.failures")
                                .tag("backlog", "outbox")
                                .counter()
                                .count())
                        .isEqualTo(failures + 1);
                assertThat(gauge("reservation.cancellation.outbox.pending")).isEqualTo(previous);
                assertThat(metrics.get("reservation.observability.backlog.last.success.timestamp")
                                .tag("backlog", "outbox")
                                .gauge()
                                .value())
                        .isEqualTo(timestamp);
            } finally {
                connection.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT current_setting('statement_timeout')", String.class))
                .isEqualTo("0");
        monitoring.refreshOutbox();
    }

    @Test
    void blockedWorkerLeavesCapacityForMonitoringAndOtherWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.Future<?> blocked = workers.submit(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            monitors.submit(monitoring::refreshExpiration).get(2, TimeUnit.SECONDS);
            assertThat(workers.submit(() -> 1).get(2, TimeUnit.SECONDS)).isOne();
            assertThat(blocked.isDone()).isFalse();
            assertThat(workers.getPoolSize()).isEqualTo(4);
            assertThat(monitors.getPoolSize()).isEqualTo(4);
        } finally {
            release.countDown();
            blocked.get(2, TimeUnit.SECONDS);
        }
    }

    private double gauge(String name) {
        return metrics.get(name).gauge().value();
    }
}
