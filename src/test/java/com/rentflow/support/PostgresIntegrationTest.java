package com.rentflow.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import(StaffHttpDefaults.class)
@Testcontainers
public abstract class PostgresIntegrationTest {
    protected static final String RESERVATION_USERNAME = "reservation";
    protected static final String RESERVATION_PASSWORD = "reservation-test";

    @Container
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("rentflow")
            .withUsername("rentflow_admin")
            .withPassword("rentflow-admin-test")
            .withInitScript("testcontainers/init-reservation.sql");

    @org.junit.jupiter.api.BeforeEach
    void authenticateServiceCalls() {
        SignedJwtFixture.authenticateStaff();
    }

    @org.junit.jupiter.api.AfterEach
    void clearServiceAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", SignedJwtFixture.SHARED::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", SignedJwtFixture.SHARED::jwks);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", SignedJwtFixture.SHARED::tokenUri);
        registry.add("spring.security.oauth2.client.registration.inventory.client-secret", () -> "isolated-test-only");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RESERVATION_USERNAME);
        registry.add("spring.datasource.password", () -> RESERVATION_PASSWORD);
        registry.add("reservation.cancellation.relay.enabled", () -> "false");
        registry.add("reservation.cancellation.cleanup.enabled", () -> "false");
        registry.add("management.server.port", () -> "0");
    }
}
