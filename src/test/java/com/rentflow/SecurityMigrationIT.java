package com.rentflow;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.*;

class SecurityMigrationIT extends PostgresIntegrationTest {
    @Test
    void upgradesV5DirectlyToSecuredV6AndResetsDevelopmentDataOnce() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), RESERVATION_USERNAME, RESERVATION_PASSWORD)
                .defaultSchema("reservation")
                .schemas("reservation")
                .target("5")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), RESERVATION_USERNAME, RESERVATION_PASSWORD));
        UUID reservation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservation.reservations
                (id, serial_number, customer_id, order_id, start_date, end_date, created_at, hold_expires_at, status)
                VALUES (?, 'UPGRADE', 'EXISTING-CUSTOMER', 'EXISTING-ORDER', DATE '2026-10-01', DATE '2026-10-03',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'CONFIRMED')
                """, reservation);
        jdbc.update("""
                INSERT INTO reservation.reservation_cancellation_outbox
                (event_id, record_key, payload, occurred_at, next_attempt_at)
                VALUES (?, 'UPGRADE', '{"preserved":true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, event);
        jdbc.update("""
                INSERT INTO reservation.reservation_creation_requests
                (idempotency_key, fingerprint, http_status, outcome, recorded_at, expires_at)
                VALUES (?, ?, 201, '{"status":201,"reservations":[]}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '168 hours')
                """, UUID.randomUUID(), "0".repeat(64));
        java.util.List<String> beforeSchemas =
                jdbc.queryForList("SELECT nspname FROM pg_namespace ORDER BY nspname", String.class);
        Flyway secured = Flyway.configure()
                .target("6")
                .dataSource(POSTGRES.getJdbcUrl(), RESERVATION_USERNAME, RESERVATION_PASSWORD)
                .defaultSchema("reservation")
                .schemas("reservation")
                .load();
        assertThat(secured.migrate().migrationsExecuted).isEqualTo(1);
        for (String table :
                java.util.List.of("reservations", "reservation_creation_requests", "reservation_cancellation_outbox")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation." + table, Integer.class))
                    .isZero();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT to_regclass('reservation.customer_identity_mappings') IS NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT nspname FROM pg_namespace ORDER BY nspname", String.class))
                .isEqualTo(beforeSchemas);
        assertThat(jdbc.queryForObject(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema='reservation' AND table_name='reservations' AND column_name='customer_issuer' AND is_nullable='NO' AND data_type='text'",
                        String.class))
                .isEqualTo("customer_issuer");
        assertThat(jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='reservation' AND indexname='idx_reservations_customer_identity'",
                        String.class))
                .contains("customer_issuer, customer_id, id");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO reservation.reservations (id,serial_number,customer_id,customer_issuer,order_id,start_date,end_date,created_at,hold_expires_at,status) VALUES (?, 'NEW', 'a', ' ', 'ORDER', CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'HELD')",
                        UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_customer_issuer_check");
        jdbc.update(
                "INSERT INTO reservation.reservations (id,serial_number,customer_id,customer_issuer,order_id,start_date,end_date,created_at,hold_expires_at,status) VALUES (?, 'NEW', ?, 'https://issuer.example', 'ORDER', CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'HELD')",
                UUID.randomUUID(),
                "a".repeat(255));
        jdbc.update(
                "INSERT INTO reservation.reservation_creation_requests (caller_scope,idempotency_key,target_customer_id,fingerprint,http_status,outcome,recorded_at,expires_at) VALUES (?, ?, ?, ?, 400, '{\"status\":400}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '168 hours')",
                "p1:" + "a".repeat(64),
                UUID.randomUUID(),
                "a".repeat(255),
                "0".repeat(64));
        jdbc.update("""
                INSERT INTO reservation.reservation_cancellation_outbox
                (event_id, record_key, payload, occurred_at, next_attempt_at)
                VALUES (?, 'NEW-EVENT', '{"preserved":true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID());
        assertThat(secured.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation.reservations", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation.reservation_creation_requests", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation.reservation_cancellation_outbox", Integer.class))
                .isOne();
        assertThat(jdbc.queryForList(
                        "SELECT version FROM reservation.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                        String.class))
                .containsExactly("1", "2", "3", "4", "5", "6");
    }
}
