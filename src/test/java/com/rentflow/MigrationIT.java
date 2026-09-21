package com.rentflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.rentflow.model.Reservation;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationIT extends PostgresIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ReservationRepository repository;

    @Autowired
    private ReservationCreationRequestRepository creationRequests;

    @Autowired
    private ReservationCancellationOutboxRepository cancellationOutboxes;

    @Test
    void usesOnlyItsRestrictedRoleAndOwnedSchema() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("reservation");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("rentflow");
        assertThat(jdbc.queryForObject(
                        "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'reservation'",
                        String.class))
                .isEqualTo("reservation");
        assertThat(jdbc.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'reservation' AND tableowner = 'reservation'",
                        String.class))
                .containsExactlyInAnyOrder(
                        "reservations",
                        "reservation_creation_requests",
                        "reservation_cancellation_outbox",
                        "flyway_schema_history");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('reservations', 'flyway_schema_history')",
                        Integer.class))
                .isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE pricing.sentinel SET marker = 'changed' WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE public.unowned (id integer)"))
                .isInstanceOf(DataAccessException.class);
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement =
                        connection.prepareStatement("SELECT marker FROM pricing.sentinel WHERE id = 1");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("untouched");
        }
    }

    @Test
    void migrationsRunOnceAndCommittedDataSurvivesRestart() {
        Instant databaseBefore = creationRequests.databaseTime();
        Reservation saved = repository.saveAndFlush(new Reservation(
                "RESTART-001", "CUSTOMER-1", "ORDER-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        Instant databaseAfter = creationRequests.databaseTime();
        assertThat(saved.getTimestamp()).isBetween(databaseBefore, databaseAfter);
        assertThat(saved.getTimestamp().getNano() % 1000).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.flyway_schema_history WHERE version IN ('1', '2', '3') AND success",
                        Integer.class))
                .isEqualTo(3);
        try (ConfigurableApplicationContext context = start(Map.of())) {
            Reservation restored = context.getBean(ReservationRepository.class)
                    .findById(saved.getId())
                    .orElseThrow();
            assertThat(restored.getTimestamp()).isEqualTo(saved.getTimestamp());
            assertThat(restored.getSerialNumber()).isEqualTo("RESTART-001");
        }
    }

    @Test
    void creationIdempotencyTableIsATerminalLedger() {
        assertThat(jdbc.queryForList("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'reservation'
                          AND table_name = 'reservation_creation_requests'
                        ORDER BY ordinal_position
                        """, String.class))
                .containsExactly(
                        "idempotency_key", "fingerprint", "http_status", "outcome", "recorded_at", "expires_at");
        assertThat(jdbc.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'reservation'
                          AND tablename = 'reservation_creation_requests'
                        """, String.class))
                .containsExactlyInAnyOrder(
                        "reservation_creation_requests_pkey", "idx_reservation_creation_requests_expiry");
    }

    @Test
    void cancellationOutboxHasTheImmutableEventAndMutableDeliveryShape() {
        assertThat(jdbc.queryForList("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'reservation'
                          AND table_name = 'reservation_cancellation_outbox'
                        ORDER BY ordinal_position
                        """, String.class))
                .containsExactly(
                        "event_id",
                        "record_key",
                        "payload",
                        "occurred_at",
                        "published_at",
                        "attempt_count",
                        "next_attempt_at",
                        "last_failure_at",
                        "last_failure_code");
        assertThat(jdbc.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'reservation'
                          AND tablename = 'reservation_cancellation_outbox'
                        """, String.class))
                .containsExactlyInAnyOrder(
                        "reservation_cancellation_outbox_pkey",
                        "idx_reservation_cancellation_outbox_fifo",
                        "idx_reservation_cancellation_outbox_cleanup");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'reservation'
                          AND table_name = 'reservation_cancellation_outbox'
                          AND constraint_type = 'FOREIGN KEY'
                        """, Integer.class)).isZero();
    }

    @Test
    void cancellationOutboxConstraintsRejectInvalidEventAndDeliveryState() {
        insertCancellationOutbox(
                UUID.randomUUID(), "DRILL-001", "{\"eventId\":\"valid\"}", null, 0, "CURRENT_TIMESTAMP", null, null);

        assertThatThrownBy(() -> insertCancellationOutbox(
                        UUID.randomUUID(), "/bad", "{\"eventId\":\"valid\"}", null, 0, "CURRENT_TIMESTAMP", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCancellationOutbox(
                        UUID.randomUUID(), "DRILL-002", "[]", null, 0, "CURRENT_TIMESTAMP", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCancellationOutbox(
                        UUID.randomUUID(),
                        "DRILL-003",
                        "{\"eventId\":1,\"eventId\":2}",
                        null,
                        0,
                        "CURRENT_TIMESTAMP",
                        null,
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCancellationOutbox(
                        UUID.randomUUID(),
                        "DRILL-004",
                        "{\"eventId\":\"valid\"}",
                        "CURRENT_TIMESTAMP",
                        -1,
                        null,
                        null,
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCancellationOutbox(
                        UUID.randomUUID(),
                        "DRILL-005",
                        "{\"eventId\":\"valid\"}",
                        null,
                        1,
                        "CURRENT_TIMESTAMP",
                        "CURRENT_TIMESTAMP",
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO reservation.reservation_cancellation_outbox (
                            event_id, record_key, payload, occurred_at, attempt_count, next_attempt_at
                        ) VALUES (?, 'DRILL-006', to_json(repeat('x', 4097))::text, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxTimeAndPendingStatisticsComeFromPostgresql() {
        insertCancellationOutbox(
                UUID.randomUUID(), "STATS-001", "{\"eventId\":\"stats\"}", null, 0, "CURRENT_TIMESTAMP", null, null);
        Instant time = cancellationOutboxes.databaseTime();
        assertThat(time).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(cancellationOutboxes.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(1);
        assertThat(cancellationOutboxes.oldestPendingOccurredAt()).isPresent();
    }

    @Test
    void creationLedgerConstraintsProtectTerminalOutcomes() {
        UUID validKey = UUID.randomUUID();
        insertCreationOutcome(validKey, "0".repeat(64), 201, "{\"status\":201}", "168 hours");

        assertThatThrownBy(() ->
                        insertCreationOutcome(UUID.randomUUID(), "not-sha256", 201, "{\"status\":201}", "168 hours"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        insertCreationOutcome(UUID.randomUUID(), "1".repeat(64), 500, "{\"status\":500}", "168 hours"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        insertCreationOutcome(UUID.randomUUID(), "2".repeat(64), 409, "{\"status\":422}", "168 hours"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        insertCreationOutcome(UUID.randomUUID(), "3".repeat(64), 409, "{\"status\":409}", "24 hours"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void constraintsProtectValuesButAllowRepeatedBusinessIdentifiers() {
        assertThat(jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'reservation' AND tablename = 'reservations'",
                        String.class))
                .containsExactlyInAnyOrder("reservations_pkey", "idx_reservations_creation_active_lookup");
        insert("DUPLICATE-001", "CUSTOMER-1", "ORDER-1", "2026-10-01", "2026-10-03", "HELD");
        insert("DUPLICATE-001", "CUSTOMER-1", "ORDER-1", "2026-10-01", "2026-10-03", "HELD");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.reservations WHERE serial_number = 'DUPLICATE-001'",
                        Integer.class))
                .isEqualTo(2);
        assertInvalid("/bad", "C", "O", "2026-10-01", "2026-10-03", "HELD");
        assertInvalid("A", " ", "O", "2026-10-01", "2026-10-03", "HELD");
        assertInvalid("A", "C", "\t", "2026-10-01", "2026-10-03", "HELD");
        assertInvalid("A", "C", "O", "2026-10-03", "2026-10-01", "HELD");
        assertInvalid("A", "C", "O", "10000-01-01", "10000-01-02", "HELD");
        assertInvalid("A", "C", "O", "2026-10-01", "2026-10-03", "EXPIRED");
    }

    @Test
    void databaseTimeAndUtcDateComeFromPostgresql() {
        Instant time = creationRequests.databaseTime();
        assertThat(creationRequests.databaseUtcDate())
                .isEqualTo(time.atOffset(java.time.ZoneOffset.UTC).toLocalDate());
        assertThat(jdbc.queryForObject(
                        "SELECT (TIMESTAMPTZ '2026-09-08 23:59:59.999999+00' AT TIME ZONE 'UTC')::date",
                        LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(jdbc.queryForObject(
                        "SELECT (TIMESTAMPTZ '2026-09-09 00:00:00+00' AT TIME ZONE 'UTC')::date", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void startupFailsWhenSchemaIsMissing() {
        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored = start(Map.of(
                            "spring.flyway.default-schema",
                            "missing_reservation",
                            "spring.flyway.schemas",
                            "missing_reservation"))) {
                        // Startup must fail before an application context can be used.
                    }
                })
                .hasStackTraceContaining("missing_reservation");
    }

    @Test
    void startupFailsWhenHibernateMappingIsIncompatible() {
        // Simulate accidental schema drift only in this disposable test database.
        jdbc.execute("ALTER TABLE reservation.reservations RENAME COLUMN serial_number TO mismatched_serial_number");
        try {
            assertThatThrownBy(() -> {
                        try (ConfigurableApplicationContext ignored = start(Map.of())) {
                            // Hibernate validation must prevent startup with the missing mapped column.
                        }
                    })
                    .hasStackTraceContaining("serial_number");
        } finally {
            jdbc.execute(
                    "ALTER TABLE reservation.reservations RENAME COLUMN mismatched_serial_number TO serial_number");
        }
    }

    private void assertInvalid(String serial, String customer, String order, String start, String end, String status) {
        assertThatThrownBy(() -> insert(serial, customer, order, start, end, status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void startupFailsWithInvalidDatabaseCredentials() {
        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored =
                            start(Map.of("spring.datasource.password", "invalid-test-password"))) {
                        // A database authentication failure must prevent startup.
                    }
                })
                .hasStackTraceContaining("password authentication failed");
    }

    @Test
    void startupFailsWhenMigrationHistoryChecksumDoesNotMatch() {
        Integer checksum = jdbc.queryForObject(
                "SELECT checksum FROM reservation.flyway_schema_history WHERE version = '1'", Integer.class);
        // Simulate migration-history corruption only in the disposable test database.
        jdbc.update("UPDATE reservation.flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        try {
            assertThatThrownBy(() -> {
                        try (ConfigurableApplicationContext ignored = start(Map.of())) {
                            // Flyway must reject a checksum mismatch before Hibernate starts.
                        }
                    })
                    .hasStackTraceContaining("checksum mismatch");
        } finally {
            jdbc.update("UPDATE reservation.flyway_schema_history SET checksum = ? WHERE version = '1'", checksum);
        }
    }

    private void insert(String serial, String customer, String order, String start, String end, String status) {
        jdbc.update(
                "INSERT INTO reservation.reservations (id, serial_number, customer_id, order_id, start_date, end_date, created_at, status) VALUES (?, ?, ?, ?, ?::date, ?::date, CURRENT_TIMESTAMP, ?)",
                UUID.randomUUID(),
                serial,
                customer,
                order,
                start,
                end,
                status);
    }

    private void insertCreationOutcome(UUID key, String fingerprint, int status, String outcome, String expiry) {
        jdbc.update("""
                INSERT INTO reservation.reservation_creation_requests (
                    idempotency_key, fingerprint, http_status, outcome, recorded_at, expires_at
                ) VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + ?::interval)
                """, key, fingerprint, status, outcome, expiry);
    }

    private void insertCancellationOutbox(
            UUID eventId,
            String recordKey,
            String payload,
            String publishedAtExpression,
            int attemptCount,
            String nextAttemptAtExpression,
            String failureAtExpression,
            String failureCode) {
        String publishedAt = publishedAtExpression == null ? "NULL" : publishedAtExpression;
        String nextAttemptAt = nextAttemptAtExpression == null ? "NULL" : nextAttemptAtExpression;
        String failureAt = failureAtExpression == null ? "NULL" : failureAtExpression;
        jdbc.update(
                """
                        INSERT INTO reservation.reservation_cancellation_outbox (
                            event_id, record_key, payload, occurred_at, published_at, attempt_count,
                            next_attempt_at, last_failure_at, last_failure_code
                        ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, %s, ?, %s, %s, ?)
                        """.formatted(publishedAt, nextAttemptAt, failureAt),
                eventId,
                recordKey,
                payload,
                attemptCount,
                failureCode);
    }

    private ConfigurableApplicationContext start(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", RESERVATION_USERNAME);
        properties.put("spring.datasource.password", RESERVATION_PASSWORD);
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.putAll(overrides);
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(ReservationApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments);
    }
}
