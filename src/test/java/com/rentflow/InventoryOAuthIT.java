package com.rentflow;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;
import com.rentflow.support.SignedJwtFixture;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(InventoryOAuthIT.Traces.class)
class InventoryOAuthIT extends PostgresIntegrationTest {
    private static final SignedJwtFixture IDENTITY = SignedJwtFixture.SHARED;
    private static final Queue<Integer> STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<List<String>> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final HttpServer INVENTORY = server();
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
    private static final String MARKER = "synthetic-private-provider-error-marker";

    @Autowired
    Environment environment;

    @Autowired
    ReservationRepository repository;

    @Autowired
    ReservationCreationRequestRepository ledger;

    @Autowired
    ReservationCancellationOutboxRepository outboxes;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    InventoryGateway gateway;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    SdkTracerProvider tracing;

    @MockitoBean(name = "reservationClock")
    Clock clock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", IDENTITY::issuer);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDENTITY::jwks);
        properties.add("spring.security.oauth2.client.provider.keycloak.token-uri", IDENTITY::tokenUri);
        properties.add(
                "spring.http.serviceclient.inventory.base-url",
                () -> "http://127.0.0.1:" + INVENTORY.getAddress().getPort());
    }

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(Instant.now());
        outboxes.deleteAllInBatch();
        ledger.deleteAllInBatch();
        repository.deleteAllInBatch();
        clients.removeAuthorizedClient("inventory", "reservation-service");
        breakers.circuitBreaker("inventory").reset();
        IDENTITY.resetTokenRequests();
        STATUSES.clear();
        REQUESTS.clear();
        tracing.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        SPANS.reset();
    }

    @AfterAll
    static void close() {
        INVENTORY.stop(0);
        IDENTITY.resetTokenRequests();
    }

    @Test
    void usesOneMachineIdentityAcrossHumansAndBackgroundCallsAndRenewsBeforeExpiry() throws Exception {
        assertThat(create("MACHINE-A", "human-a", UUID.randomUUID()).statusCode())
                .isEqualTo(201);
        assertThat(create("MACHINE-B", "human-b", UUID.randomUUID()).statusCode())
                .isEqualTo(201);
        SecurityContextHolder.clearContext();
        assertThat(gateway.claim(UUID.randomUUID(), List.of("BACKGROUND")).type())
                .isEqualTo(InventoryGateway.ClaimResult.Type.CLAIMED);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        assertThat(REQUESTS).hasSize(3).allSatisfy(request -> {
            assertThat(request.get(0)).startsWith("Bearer fixture-service-");
            assertThat(request.get(2)).isEqualTo("absent");
            assertThat(request.get(3)).contains("RESERVED").doesNotContain("AVAILABLE");
        });
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient("inventory", "reservation-service");
        when(clock.instant()).thenReturn(client.getAccessToken().getExpiresAt().minusSeconds(31));
        gateway.claim(UUID.randomUUID(), List.of("BEFORE-RENEWAL"));
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        when(clock.instant()).thenReturn(client.getAccessToken().getExpiresAt().minusSeconds(29));
        gateway.claim(UUID.randomUUID(), List.of("RENEWED"));
        assertThat(IDENTITY.tokenRequests()).isEqualTo(2);
        assertThat(IDENTITY.tokenAuthorization())
                .containsOnly("Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "reservation-service:isolated-test-only".getBytes(StandardCharsets.UTF_8)));
        assertThat(IDENTITY.tokenBodies())
                .allSatisfy(body -> assertThat(body)
                        .contains("grant_type=client_credentials")
                        .doesNotContain("scope=", "openid", "client_secret"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void failsOnceOnInventoryAuthErrorsAndEvictsOnlyFor401(int status) throws Exception {
        STATUSES.add(status);
        UUID key = UUID.randomUUID();
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow")) {
            assertFailure(create("AUTH-FAIL", "staff", key), 502);
            assertThat(logs.events())
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        }
        assertThat(REQUESTS).hasSize(1);
        String downstreamKey = REQUESTS.peek().get(1);
        OAuth2AuthorizedClient retained = clients.loadAuthorizedClient("inventory", "reservation-service");
        assertThat(retained == null).isEqualTo(status == 401);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        assertThat(breakers.circuitBreaker("inventory").getMetrics().getNumberOfBufferedCalls())
                .isZero();
        assertThat(create("AUTH-FAIL", "staff", key).statusCode()).isEqualTo(201);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(status == 401 ? 2 : 1);
        assertThat(REQUESTS).allSatisfy(request -> assertThat(request.get(1)).isEqualTo(downstreamKey));
    }

    @ParameterizedTest
    @CsvSource({"400,502", "401,502", "403,502", "429,503", "500,503", "503,503", "302,502"})
    void mapsAcquisitionStatusWithoutResourceCallsOrRetry(int status, int expected) throws Exception {
        IDENTITY.reply(status, "{\"error\":\"invalid_client\",\"error_description\":\"" + MARKER + "\"}");
        assertFailure(create("TOKEN-FAIL", "staff", UUID.randomUUID()), expected);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        assertThat(REQUESTS).isEmpty();
        assertThat(breakers.circuitBreaker("inventory").getMetrics().getNumberOfBufferedCalls())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-json",
                "{}",
                "{\"token_type\":\"Bearer\",\"expires_in\":300}",
                "{\"access_token\":\" \",\"token_type\":\"Bearer\",\"expires_in\":300}",
                "{\"access_token\":\"value\",\"token_type\":\"Bearer\"}",
                "{\"access_token\":\"value\",\"token_type\":\"MAC\",\"expires_in\":300}",
                "{\"access_token\":\"value\",\"token_type\":\"DPoP\",\"expires_in\":300}",
                "{\"access_token\":\"value\",\"token_type\":\"Bearer\",\"expires_in\":0}",
                "{\"access_token\":\"value\",\"token_type\":\"Bearer\",\"expires_in\":-1}"
            })
    void rejectsInvalidTokenResponsesWithoutCachingOrFallback(String body) throws Exception {
        IDENTITY.reply(200, body);
        assertFailure(create("MALFORMED", "staff", UUID.randomUUID()), 502);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        assertThat(REQUESTS).isEmpty();
        assertThat((Object) clients.loadAuthorizedClient("inventory", "reservation-service"))
                .isNull();
    }

    @Test
    void boundsTokenTimeoutAndKeepsLocalReadsIndependent() throws Exception {
        IDENTITY.tokenDelay(2500);
        long started = System.nanoTime();
        assertFailure(create("TIMEOUT", "staff", UUID.randomUUID()), 503);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        assertThat(REQUESTS).isEmpty();
        assertThat(request("GET", "/api/v1/reservations", null, "staff", null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/api/v1/reservations/" + UUID.randomUUID(), null, "staff", null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
    }

    @Test
    void neverExportsProviderSecretsOrHumanMarkersInErrorsLogsOrSpans() throws Exception {
        IDENTITY.reply(401, "{\"error\":\"invalid_client\",\"error_description\":\"" + MARKER + "\"}");
        try (EcsLogCapture capture = new EcsLogCapture("com.rentflow")) {
            assertFailure(create("LEAKAGE", "private-subject-marker", UUID.randomUUID()), 502);
            assertThat(capture.events())
                    .allSatisfy(event -> assertThat(
                                    EcsLogCapture.encode(event, environment).toString())
                            .doesNotContain(MARKER, "private-subject-marker", "isolated-test-only"));
        }
        tracing.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(SPANS.getFinishedSpanItems()).isNotEmpty();
        assertThat(SPANS.getFinishedSpanItems())
                .allSatisfy(span -> assertThat(span.toString())
                        .doesNotContain(MARKER, "private-subject-marker", "isolated-test-only"));
    }

    @Test
    void resourceRedirectAndOpenCircuitDoNotLeakCredentialsOrTriggerAuthenticationRetries() throws Exception {
        STATUSES.add(302);
        assertFailure(create("REDIRECT", "staff", UUID.randomUUID()), 502);
        assertThat(REQUESTS).hasSize(1);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        clients.removeAuthorizedClient("inventory", "reservation-service");
        breakers.circuitBreaker("inventory").transitionToOpenState();
        assertFailure(create("OPEN", "staff", UUID.randomUUID()), 503);
        assertThat(REQUESTS).hasSize(1);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
    }

    @Test
    void resourceRetriesReuseTheDerivedKeyAndOneMachineToken() throws Exception {
        STATUSES.addAll(List.of(503, 502, 504, 503));
        assertThat(create("RETRY", "staff", UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(REQUESTS).hasSize(5);
        assertThat(REQUESTS.stream().map(row -> row.get(1)).distinct()).hasSize(1);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
    }

    @Test
    void cachedMachineTokenSurvivesProviderOutageUntilRenewalAndThenRecovers() {
        SecurityContextHolder.clearContext();
        gateway.claim(UUID.randomUUID(), List.of("WARM"));
        OAuth2AuthorizedClient authorized = clients.loadAuthorizedClient("inventory", "reservation-service");
        IDENTITY.reply(503, "unavailable");
        gateway.claim(UUID.randomUUID(), List.of("CACHED-DURING-OUTAGE"));
        assertThat(IDENTITY.tokenRequests()).isEqualTo(1);
        when(clock.instant()).thenReturn(authorized.getAccessToken().getExpiresAt());
        assertThatThrownBy(() -> gateway.claim(UUID.randomUUID(), List.of("RENEWAL-UNAVAILABLE")))
                .isInstanceOf(InventoryGateway.ServiceUnavailableException.class);
        assertThat(IDENTITY.tokenRequests()).isEqualTo(2);
        assertThat(REQUESTS).hasSize(2);
        gateway.claim(UUID.randomUUID(), List.of("RECOVERED"));
        assertThat(IDENTITY.tokenRequests()).isEqualTo(3);
        assertThat(REQUESTS).hasSize(3);
    }

    private void assertFailure(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body())
                .contains(status == 502 ? "INVENTORY_SERVICE_ERROR" : "INVENTORY_SERVICE_UNAVAILABLE")
                .doesNotContain(MARKER, "invalid_client", "isolated-test-only");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(repository.count()).isZero();
        assertThat(ledger.count()).isZero();
    }

    private HttpResponse<String> create(String serial, String subject, UUID key) throws Exception {
        String date = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
        String body = "{\"orderId\":\"ORDER\",\"items\":[{\"serialNumber\":\"" + serial + "\",\"startDate\":\"" + date
                + "\",\"endDate\":\"" + date + "\"}]}";
        return request("POST", "/api/v1/reservations", body, subject, key);
    }

    private HttpResponse<String> request(String method, String path, String body, String subject, UUID key)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Bearer " + IDENTITY.token(subject, "reservation-swagger", SignedJwtFixture.STAFF));
        if (key != null) builder.header("Idempotency-Key", key.toString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                REQUESTS.add(List.of(
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "absent"),
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Idempotency-Key"), "absent"),
                        java.util.Objects.toString(exchange.getRequestHeaders().getFirst("X-User-Id"), "absent"),
                        body));
                Integer status = STATUSES.poll();
                exchange.getResponseHeaders()
                        .set(
                                "Location",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
                exchange.sendResponseHeaders(status == null ? 204 : status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Traces {
        @Bean
        SpanExporter securityOutboundExporter() {
            return SPANS;
        }
    }
}
