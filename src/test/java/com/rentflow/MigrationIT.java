package com.rentflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
                .containsExactlyInAnyOrder("reservations", "flyway_schema_history");
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
        Reservation saved = repository.saveAndFlush(new Reservation(
                "RESTART-001", "CUSTOMER-1", "ORDER-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.flyway_schema_history WHERE version = '1' AND success",
                        Integer.class))
                .isOne();
        try (ConfigurableApplicationContext context = start(Map.of())) {
            Reservation restored = context.getBean(ReservationRepository.class)
                    .findById(saved.getId())
                    .orElseThrow();
            assertThat(restored.getTimestamp()).isEqualTo(saved.getTimestamp());
            assertThat(restored.getSerialNumber()).isEqualTo("RESTART-001");
        }
    }

    @Test
    void constraintsProtectValuesButAllowRepeatedBusinessIdentifiers() {
        assertThat(jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'reservation' AND tablename = 'reservations'",
                        String.class))
                .containsExactly("reservations_pkey");
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
