package com.rentflow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReservationHoldExpirationMigrationIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("rentflow")
            .withUsername("reservation")
            .withPassword("reservation-test");

    @Test
    void backfillsReservationDeadlinesAndSuccessfulCreationSnapshots() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA reservation AUTHORIZATION reservation");
        }
        migrate("3");

        UUID reservationId = UUID.randomUUID();
        UUID successKey = UUID.randomUUID();
        UUID failureKey = UUID.randomUUID();
        String timestamp = "2026-09-01T12:00:00.123456Z";
        try (Connection connection = connection()) {
            try (PreparedStatement reservation = connection.prepareStatement("""
                    INSERT INTO reservation.reservations (
                        id, serial_number, customer_id, order_id, start_date, end_date, created_at, status
                    ) VALUES (?, 'MIGRATE-001', 'CUSTOMER-001', 'ORDER-001',
                        DATE '2026-10-01', DATE '2026-10-03', ?::timestamptz, 'HELD')
                    """)) {
                reservation.setObject(1, reservationId);
                reservation.setString(2, timestamp);
                reservation.executeUpdate();
            }
            insertOutcome(connection, successKey, 201, """
                    {"status":201,"type":null,"title":null,"detail":null,"instance":null,"code":null,
                     "reservations":[{"id":"%s","serialNumber":"MIGRATE-001","customerId":"CUSTOMER-001",
                     "orderId":"ORDER-001","startDate":"2026-10-01","endDate":"2026-10-03",
                     "timestamp":"%s","status":"HELD"}],"failedItems":[],"violations":[]}
                    """.formatted(reservationId, timestamp));
            insertOutcome(connection, failureKey, 409, """
                    {"status":409,"type":"about:blank","title":"Conflict","detail":"Conflict",
                     "instance":"/api/v1/reservations","code":"ACTIVE_RESERVATION_EXISTS",
                     "reservations":[],"failedItems":[],"violations":[]}
                    """);
        }

        migrate(null);

        ObjectMapper mapper = new ObjectMapper();
        try (Connection connection = connection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT hold_expires_at FROM reservation.reservations WHERE id = ?")) {
                statement.setObject(1, reservationId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1, OffsetDateTime.class).toInstant())
                            .isEqualTo(Instant.parse("2026-09-01T12:10:00.123456Z"));
                }
            }
            JsonNode success = mapper.readTree(outcome(connection, successKey));
            assertThat(Instant.parse(success.at("/reservations/0/holdExpiresAt").asString()))
                    .isEqualTo(Instant.parse("2026-09-01T12:10:00.123456Z"));
            JsonNode failure = mapper.readTree(outcome(connection, failureKey));
            assertThat(failure.at("/reservations/0/holdExpiresAt").isMissingNode())
                    .isTrue();
        }
    }

    private void migrate(String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("reservation")
                .schemas("reservation");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertOutcome(Connection connection, UUID key, int status, String outcome) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reservation.reservation_creation_requests (
                    idempotency_key, fingerprint, http_status, outcome, recorded_at, expires_at
                ) VALUES (?, ?, ?, ?::jsonb, TIMESTAMPTZ '2026-09-01T12:00:00Z',
                    TIMESTAMPTZ '2026-09-08T12:00:00Z')
                """)) {
            statement.setObject(1, key);
            statement.setString(2, "0".repeat(64));
            statement.setInt(3, status);
            statement.setString(4, outcome);
            statement.executeUpdate();
        }
    }

    private String outcome(Connection connection, UUID key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT outcome::text FROM reservation.reservation_creation_requests WHERE idempotency_key = ?")) {
            statement.setObject(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
