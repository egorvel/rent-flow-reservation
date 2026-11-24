package com.rentflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityStartupIT extends PostgresIntegrationTest {
    @Test
    void startsActualListenersWithUnreachableIdentityAndInventoryWithoutAddingReadinessDependencies() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", RESERVATION_USERNAME);
        properties.put("spring.datasource.password", RESERVATION_PASSWORD);
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://127.0.0.1:1/realms/test");
        properties.put(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "http://127.0.0.1:1/realms/test/protocol/openid-connect/certs");
        properties.put(
                "spring.security.oauth2.client.provider.keycloak.token-uri",
                "http://127.0.0.1:1/realms/test/protocol/openid-connect/token");
        properties.put(
                "spring.security.oauth2.client.registration.inventory.client-secret", "isolated-cold-start-test");
        properties.put("logging.level.root", "OFF");
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(ReservationApplication.class)
                        .profiles("test")
                        .properties(properties)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.port=0",
                                "--management.server.port=0",
                                "--spring.http.serviceclient.inventory.base-url=http://127.0.0.1:1");
                HttpClient client = HttpClient.newHttpClient()) {
            String origin = "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
            for (String path : new String[] {"/livez", "/readyz"}) {
                HttpResponse<String> probe = client.send(
                        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(probe.statusCode()).isEqualTo(200);
                assertThat(probe.body()).isEqualTo("{\"status\":\"UP\"}");
            }
            HttpResponse<String> protectedRequest = client.send(
                    HttpRequest.newBuilder(URI.create(origin + "/api/v1/reservations"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(protectedRequest.statusCode()).isEqualTo(401);
            HttpResponse<String> discovery = client.send(
                    HttpRequest.newBuilder(URI.create(origin + "/.well-known/oauth-protected-resource"))
                            .header("Authorization", "Bearer malformed")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(discovery.statusCode()).isEqualTo(200);
            assertThat(discovery.body()).contains("http://127.0.0.1:1/realms/test");
        }
    }
}
