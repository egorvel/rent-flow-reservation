package com.rentflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.nimbusds.jose.JWSAlgorithm;
import com.rentflow.converter.ReservationAuthorityConverter;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.IdempotencyFingerprint;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.service.ReservationCreationService;
import com.rentflow.service.ReservationService;
import com.rentflow.service.ReservationSortField;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;
import com.rentflow.support.SignedJwtFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
class SecurityIT extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/reservations";
    private static final SignedJwtFixture TOKENS = SignedJwtFixture.SHARED;

    @Autowired
    Environment environment;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReservationCreationRequestRepository ledger;

    @Autowired
    ReservationCancellationOutboxRepository outboxes;

    @Autowired
    ReservationService reservations;

    @Autowired
    ReservationCreationService creation;

    @Autowired
    ReservationCancellationService cancellation;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @MockitoSpyBean
    ReservationRepository repository;

    @MockitoBean
    InventoryGateway inventory;

    @BeforeEach
    void prepare() {
        outboxes.deleteAllInBatch();
        ledger.deleteAllInBatch();
        repository.deleteAllInBatch();
        reset(inventory);
        when(inventory.claim(any(), any())).thenReturn(InventoryGateway.ClaimResult.claimed());
        clearInvocations(repository, inventory);
    }

    @Test
    void scopesEveryCustomerOperationAndPageTotalAndConcealsForeignRows() throws Exception {
        JsonNode a = created("a", "ITEM-A", UUID.randomUUID());
        JsonNode b = created("b", "ITEM-B", UUID.randomUUID());
        String aId = a.get(0).path("id").asString();
        String bId = b.get(0).path("id").asString();
        assertThat(call("GET", PATH + "/" + aId, null, customer("a"), null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> foreign = call("GET", PATH + "/" + bId, null, customer("a"), null);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(json(foreign).path("detail").asString()).isEqualTo("Reservation was not found.");
        assertThat(call("POST", PATH + "/" + bId + "/cancel", null, customer("a"), null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(outboxes.count()).isZero();
        for (String query : List.of("", "?sort=customerId&direction=desc", "?page=8&size=1")) {
            JsonNode page = json(call("GET", PATH + query, null, customer("a"), null));
            assertThat(page.at("/page/totalElements").asLong()).isEqualTo(1);
            for (JsonNode row : page.path("content")) {
                assertThat(row.path("customerId").asString()).isEqualTo("a");
            }
        }
        assertThat(call("POST", PATH + "/" + aId + "/cancel", null, customer("a"), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(call("POST", PATH + "/" + aId + "/cancel", null, customer("a"), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(call("POST", PATH + "/" + aId + "/cancel", null, customer("b"), null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(outboxes.count()).isOne();
        assertThat(repository.findById(UUID.fromString(bId)).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void acceptsNewIdentityWithoutProvisioningAndRejectsOwnerInputsBeforeBusinessWork() throws Exception {
        for (String field : List.of("customerId", "customerIssuer")) {
            String input = body("FORBIDDEN").replaceFirst("\\{", "{\"" + field + "\":\"foreign\",");
            assertThat(call("POST", PATH, input, customer("new-user"), UUID.randomUUID())
                            .statusCode())
                    .isEqualTo(400);
        }
        verifyNoInteractions(repository, inventory);
        assertThat(ledger.count()).isZero();
        JsonNode page = json(call("GET", PATH, null, customer("new-user"), null));
        assertThat(page.at("/page/totalElements").asLong()).isZero();
        assertThat(call("GET", PATH + "/" + UUID.randomUUID(), null, customer("new-user"), null)
                        .statusCode())
                .isEqualTo(404);
        JsonNode rows = created("new-user", "NEW-USER", UUID.randomUUID());
        assertThat(rows.get(0).path("customerId").asString()).isEqualTo("new-user");
        assertThat(rows.get(0).has("customerIssuer")).isFalse();
        assertThat(repository
                        .findById(UUID.fromString(rows.get(0).path("id").asString()))
                        .orElseThrow()
                        .getCustomerIssuer())
                .isEqualTo(TOKENS.issuer());
        assertThat(call("GET", PATH + "?customerId=B", null, customer("a"), null)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void ownershipUsesSubjectEvenWithForeignClaimsAndLongOpaqueSubjects() throws Exception {
        String subject = "opaque-" + "s".repeat(200);
        Map<String, Object> claims = TOKENS.claims(subject, "reservation-swagger", SignedJwtFixture.CUSTOMER);
        claims.put("customerId", "foreign-customer");
        claims.put("email", "someone@example.test");
        claims.put("preferred_username", "foreign-user");
        String token = TOKENS.token(claims, JWSAlgorithm.RS256, "JWT");
        JsonNode result = json(call("POST", PATH, body("OPAQUE-SUBJECT"), token, UUID.randomUUID()));
        assertThat(result.get(0).path("customerId").asString()).isEqualTo(subject);
        String id = result.get(0).path("id").asString();
        assertThat(call("GET", PATH + "/" + id, null, customer("foreign-customer"), null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(json(call("GET", PATH, null, token, null))
                        .at("/page/totalElements")
                        .asLong())
                .isOne();
    }

    @Test
    void makesEightPermissionsIndependentAndRejectsOvergrantedMachineClients() throws Exception {
        UUID missing = UUID.randomUUID();
        for (String permission : List.of("reservation:list", "reservation:list:any")) {
            String token = token("a", List.of(permission));
            assertThat(call("GET", PATH, null, token, null).statusCode()).isEqualTo(200);
            assertDenial(call("GET", PATH + "/" + missing, null, token, null), 403);
            assertDenial(call("POST", PATH, "{", token, UUID.randomUUID()), 403);
        }
        for (String permission : List.of("reservation:read", "reservation:read:any")) {
            String token = token("a", List.of(permission));
            assertThat(call("GET", PATH + "/" + missing, null, token, null).statusCode())
                    .isEqualTo(404);
            assertDenial(call("GET", PATH, null, token, null), 403);
        }
        for (String permission : List.of("reservation:cancel", "reservation:cancel:any")) {
            String token = token("a", List.of(permission));
            assertThat(call("POST", PATH + "/" + missing + "/cancel", null, token, null)
                            .statusCode())
                    .isEqualTo(404);
            assertDenial(call("GET", PATH, null, token, null), 403);
        }
        String manage = token("unmapped-staff", List.of("reservation:manage"));
        assertThat(call("DELETE", PATH + "/" + missing, null, manage, null).statusCode())
                .isEqualTo(404);
        assertDenial(call("POST", PATH, "{", manage, UUID.randomUUID()), 403);
        assertDenial(call("GET", PATH, null, manage, null), 403);
        for (String client : List.of("reservation-service", "pricing-service", "inventory-swagger", "unknown")) {
            String overgranted = TOKENS.token("machine", client, SignedJwtFixture.STAFF);
            assertDenial(call("GET", PATH, null, overgranted, null), 403);
            assertDenial(call("POST", PATH, "{", overgranted, UUID.randomUUID()), 403);
        }
        assertThat(call(
                                "POST",
                                PATH,
                                body("STAFF-CREATE"),
                                token("unmapped-staff", List.of("reservation:create")),
                                UUID.randomUUID())
                        .statusCode())
                .isEqualTo(201);
        assertDenial(
                call("POST", PATH, "{", token("staff", List.of("reservation:create:any")), UUID.randomUUID()), 403);
        JsonNode own = json(
                call("POST", PATH, body("OWN-CREATE"), token("a", List.of("reservation:create")), UUID.randomUUID()));
        assertThat(own.get(0).path("customerId").asString()).isEqualTo("a");
    }

    @Test
    void isolatesExternalKeysAndRechecksPermissionsAndSavedRowOwnership() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode a = created("a", "REPLAY-A", key);
        created("b", "REPLAY-B", key);
        assertThat(ledger.count()).isEqualTo(2);
        String aScope = IdempotencyFingerprint.callerScope(TOKENS.issuer(), "a");
        String bScope = IdempotencyFingerprint.callerScope(TOKENS.issuer(), "b");
        verify(inventory).claim(IdempotencyFingerprint.inventoryKey(aScope, key), List.of("REPLAY-A"));
        verify(inventory).claim(IdempotencyFingerprint.inventoryKey(bScope, key), List.of("REPLAY-B"));
        assertThat(IdempotencyFingerprint.inventoryKey(aScope, key))
                .isNotEqualTo(IdempotencyFingerprint.inventoryKey(bScope, key));
        HttpResponse<String> replay = call(
                "POST",
                PATH,
                body("REPLAY-A"),
                TOKENS.token("a", "rentflow-pkce-check", SignedJwtFixture.CUSTOMER),
                key);
        assertThat(json(replay)).isEqualTo(a);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertDenial(call("POST", PATH, body("REPLAY-A"), token("a", List.of("reservation:read")), key), 403);
        UUID id = UUID.fromString(a.get(0).path("id").asString());
        jdbc.update(
                "UPDATE reservation.reservation_creation_requests SET target_customer_id='other' WHERE caller_scope=?",
                aScope);
        assertDenial(call("POST", PATH, body("REPLAY-A"), customer("a"), key), 403);
        jdbc.update(
                "UPDATE reservation.reservation_creation_requests SET target_customer_id='a' WHERE caller_scope=?",
                aScope);
        repository.deleteById(id);
        HttpResponse<String> denied = call("POST", PATH, body("REPLAY-A"), customer("a"), key);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.headers().firstValue("Idempotency-Replayed")).isEmpty();
        verify(inventory, times(2)).claim(any(), any());
    }

    @Test
    void staffCreationIsSelfOwnedAndCannotReplayAfterDeletion() throws Exception {
        UUID key = UUID.randomUUID();
        String token = token("staff-only-create", List.of("reservation:create"));
        JsonNode created = json(call("POST", PATH, body("HISTORY"), token, key));
        UUID id = UUID.fromString(created.get(0).path("id").asString());
        assertThat(created.get(0).path("customerId").asString()).isEqualTo("staff-only-create");
        repository.deleteById(id);
        HttpResponse<String> denied = call("POST", PATH, body("HISTORY"), token, key);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void staffReplacementPreservesForeignOwnerAndRejectsOwnerInputs() throws Exception {
        JsonNode original = created("a", "REPLACE-ORIGINAL", UUID.randomUUID()).get(0);
        String id = original.path("id").asString();
        String date = original.path("startDate").asString();
        String replacement = "{\"serialNumber\":\"REPLACED\",\"orderId\":\"CHANGED\",\"startDate\":\"" + date
                + "\",\"endDate\":\"" + date + "\",\"status\":\"CONFIRMED\"}";
        for (String field : List.of("customerId", "customerIssuer")) {
            String invalid = replacement.replaceFirst("\\{", "{\"" + field + "\":\"staff\",");
            assertThat(call("PUT", PATH + "/" + id, invalid, TOKENS.staff(), null)
                            .statusCode())
                    .isEqualTo(400);
        }
        HttpResponse<String> response = call("PUT", PATH + "/" + id, replacement, TOKENS.staff(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json(response);
        assertThat(result.path("customerId").asString()).isEqualTo("a");
        assertThat(result.path("timestamp")).isEqualTo(original.path("timestamp"));
        assertThat(result.path("holdExpiresAt")).isEqualTo(original.path("holdExpiresAt"));
        assertThat(result.path("serialNumber").asString()).isEqualTo("REPLACED");
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getCustomerIssuer())
                .isEqualTo(TOKENS.issuer());
        assertThat(call("GET", PATH + "/" + id, null, customer("a"), null).statusCode())
                .isEqualTo(200);
        assertThat(call("GET", PATH + "/" + id, null, customer("staff"), null).statusCode())
                .isEqualTo(404);
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void proxiedServicesRequireCurrentCapabilitiesBeforeAnyDataAccess() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> reservations.get(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reservations.list(0, 20, ReservationSortField.ID, Sort.Direction.ASC))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> cancellation.cancel(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> creation.create(UUID.randomUUID(), null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository, inventory);
    }

    @Test
    void cancellationWaitingOnStaffDeletionCannotMutateDeletedRow() throws Exception {
        LocalDate date = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        Reservation original = repository.saveAndFlush(new Reservation(
                "OWNER-RACE", "a", com.rentflow.support.SignedJwtFixture.SHARED.issuer(), "ORDER", date, date));
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> pending =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(_ -> {
                Reservation locked =
                        repository.findForUpdateById(original.getId()).orElseThrow();
                pending.set(executor.submit(() -> {
                    Map<String, Object> claims =
                            TOKENS.claims("a", "reservation-swagger", List.of("reservation:cancel"));
                    claims.put("iat", Instant.now());
                    claims.put("exp", Instant.now().plusSeconds(300));
                    Jwt jwt = Jwt.withTokenValue("in-memory-proxy-test")
                            .header("alg", "RS256")
                            .claims(values -> values.putAll(claims))
                            .build();
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new JwtAuthenticationToken(jwt, new ReservationAuthorityConverter().convert(jwt)));
                    try {
                        assertThatThrownBy(() -> cancellation.cancel(original.getId()))
                                .isInstanceOf(com.rentflow.service.ReservationNotFoundException.class);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                do {
                    // PostgreSQL caches pg_stat_activity within this writer transaction.
                    // Refresh that diagnostic snapshot while waiting for the second connection.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked = jdbc.queryForObject(
                            "SELECT count(*) > 0 FROM pg_stat_activity WHERE usename = current_user AND wait_event_type = 'Lock' AND query LIKE '%for%update%'",
                            Boolean.class);
                    if (blocked) break;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                } while (System.nanoTime() < deadline);
                assertThat(blocked)
                        .as("customer cancellation reached the locked ownership query")
                        .isTrue();
                repository.delete(locked);
            });
            pending.get().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(repository.findById(original.getId())).isEmpty();
        assertThat(outboxes.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "sub", "azp", "aud", "iat", "exp", "typ"})
    void rejectsMissingRequiredClaimsBeforeAnyBusinessWork(String claim) throws Exception {
        Map<String, Object> claims = TOKENS.claims("a", "reservation-swagger", SignedJwtFixture.CUSTOMER);
        claims.remove(claim);
        assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, "JWT"), null), 401);
        verifyNoInteractions(repository, inventory);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "wrong-issuer",
                "wrong-audience",
                "empty-audience",
                "bad-audience-member",
                "blank-sub",
                "blank-azp",
                "expired",
                "future",
                "nbf",
                "id-token",
                "roles",
                "iat-string"
            })
    void rejectsInvalidSignedClaims(String mode) throws Exception {
        Map<String, Object> claims = TOKENS.claims("a", "reservation-swagger", SignedJwtFixture.CUSTOMER);
        switch (mode) {
            case "wrong-issuer" -> claims.put("iss", "https://other.example/realm");
            case "wrong-audience" -> claims.put("aud", List.of("inventory-api"));
            case "empty-audience" -> claims.put("aud", List.of());
            case "bad-audience-member" -> claims.put("aud", List.of("reservation-api", ""));
            case "blank-sub" -> claims.put("sub", " ");
            case "blank-azp" -> claims.put("azp", " ");
            case "expired" -> {
                claims.put("iat", Instant.now().minusSeconds(400).getEpochSecond());
                claims.put("exp", Instant.now().minusSeconds(40).getEpochSecond());
            }
            case "future" -> claims.put("iat", Instant.now().plusSeconds(40).getEpochSecond());
            case "nbf" -> claims.put("nbf", Instant.now().plusSeconds(40).getEpochSecond());
            case "id-token" -> claims.put("typ", "ID");
            case "roles" ->
                claims.put("resource_access", Map.of("reservation-api", Map.of("roles", "reservation:list")));
            case "iat-string" -> claims.put("iat", "bad-timestamp");
            default -> throw new IllegalArgumentException(mode);
        }
        assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, "JWT"), null), 401);
        verifyNoInteractions(repository, inventory);
    }

    @Test
    void acceptsSharedClaimNormalizationAndRejectsWrongSigningTypesAndForeignAuthorities() throws Exception {
        Map<String, Object> claims = TOKENS.claims("42", "reservation-swagger", List.of("reservation:list:any"));
        claims.put("sub", 42);
        claims.put("aud", "reservation-api");
        claims.put("iat", Instant.now().minusSeconds(3600).getEpochSecond());
        assertThat(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, "jwt"), null)
                        .statusCode())
                .isEqualTo(200);
        for (String type : List.of("at+jwt", "", "ID")) {
            assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, type), null), 401);
        }
        assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, null), null), 401);
        assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS512, "JWT"), null), 401);
        try (SignedJwtFixture foreign = new SignedJwtFixture()) {
            assertDenial(call("GET", PATH, null, foreign.token(claims, JWSAlgorithm.RS256, "JWT"), null), 401);
        }
        claims.put("resource_access", Map.of("inventory-api", Map.of("roles", SignedJwtFixture.STAFF)));
        claims.put("realm_access", Map.of("roles", List.of("STAFF", "reservation:list:any")));
        claims.put("scope", "reservation:list:any");
        assertDenial(call("GET", PATH, null, TOKENS.token(claims, JWSAlgorithm.RS256, "JWT"), null), 403);
    }

    @Test
    void preservesProbesAndPrivateManagementAndStatelessDenials() throws Exception {
        for (String path : List.of("/livez", "/readyz")) {
            HttpResponse<String> response = call("GET", path, null, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).propertyNames()).containsExactly("status");
        }
        assertDenial(call("GET", PATH, null, null, null), 401);
        assertThat(call("GET", "/actuator/prometheus", null, TOKENS.staff(), null)
                        .statusCode())
                .isEqualTo(403);
        for (String path :
                List.of(PATH, "/actuator", "/swagger-ui/index.html", "/.well-known/oauth-protected-resource")) {
            HttpResponse<String> denied = callPort("local.management.port", "GET", path, null, "invalid", null);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.headers().firstValue("WWW-Authenticate")).isEmpty();
        }
        for (String path : List.of(
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/prometheus")) {
            assertThat(callPort("local.management.port", "GET", path, null, null, null)
                            .statusCode())
                    .isEqualTo(200);
        }
        for (String method : List.of("HEAD", "OPTIONS", "PATCH")) {
            assertThat(call(method, PATH, null, TOKENS.staff(), null).statusCode())
                    .isEqualTo(403);
        }
        assertDenial(call("GET", "/unconfigured", null, TOKENS.staff(), null), 403);
        assertDenial(call("GET", "/v3/api-docs", null, TOKENS.staff(), null), 403);
    }

    @Test
    void publicNativeMetadataPrecedesBearerChecksAndUsesNativeResourceUrls() throws Exception {
        for (String suffix : List.of("", "/", "/api/v1/reservations", "/a%20b/")) {
            String path = "/.well-known/oauth-protected-resource" + suffix;
            JsonNode anonymous = json(call("GET", path + "?private=query", null, null, null));
            assertThat(anonymous.propertyNames())
                    .containsExactlyInAnyOrder(
                            "resource",
                            "authorization_servers",
                            "bearer_methods_supported",
                            "tls_client_certificate_bound_access_tokens");
            assertThat(anonymous.path("resource").asString()).doesNotContain("?", "private");
            assertThat(anonymous.path("authorization_servers").get(0).asString())
                    .isEqualTo(TOKENS.issuer());
            for (String bearer : List.of("malformed", TOKENS.staff())) {
                assertThat(json(call("GET", path, null, bearer, null))).isEqualTo(anonymous);
            }
        }
    }

    @Test
    void forwardingHeadersCannotMoveRequestsBetweenTheActualListeners() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String port : List.of("local.server.port", "local.management.port")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(origin(port) + PATH))
                        .header("Authorization", "Bearer " + TOKENS.staff())
                        .header("X-Forwarded-Port", environment.getRequiredProperty("local.management.port"))
                        .header("X-Forwarded-Host", "127.0.0.1:" + environment.getRequiredProperty("local.server.port"))
                        .header("Forwarded", "host=127.0.0.1;proto=https")
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(port.equals("local.server.port") ? 200 : 403);
            }
        }
    }

    @Test
    void rejectsDuplicateAndQueryCredentialsAndLeaksNoSyntheticSecurityMarkers() throws Exception {
        String marker = "synthetic-security-private-marker";
        try (EcsLogCapture capture = new EcsLogCapture("com.rentflow.config.ReservationSecurityResponses")) {
            HttpResponse<String> invalid = call("GET", PATH, null, marker, null);
            assertDenial(invalid, 401);
            assertThat(invalid.body()).doesNotContain(marker);
            assertThat(capture.events().toString()).doesNotContain(marker);
        }
        HttpRequest duplicate = HttpRequest.newBuilder(URI.create(origin("local.server.port") + PATH))
                .header("Authorization", "Bearer " + TOKENS.staff())
                .header("Authorization", "Bearer " + TOKENS.staff())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertDenial(client.send(duplicate, HttpResponse.BodyHandlers.ofString()), 401);
        }
        assertDenial(call("GET", PATH + "?access_token=" + TOKENS.staff(), null, null, null), 401);
    }

    private JsonNode created(String subject, String serial, UUID key) throws Exception {
        HttpResponse<String> response = call("POST", PATH, body(serial), customer(subject), key);
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response);
    }

    private static String body(String serial) {
        String date = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
        return "{\"orderId\":\"SAME-UNVERIFIED-ORDER\",\"items\":[{\"serialNumber\":\"" + serial + "\",\"startDate\":\""
                + date + "\",\"endDate\":\"" + date + "\"}]}";
    }

    private String customer(String subject) {
        return token(subject, SignedJwtFixture.CUSTOMER);
    }

    private String token(String subject, List<String> roles) {
        return TOKENS.token(subject, "reservation-swagger", roles);
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private URI origin(String property) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty(property));
    }

    private HttpResponse<String> call(String method, String path, String body, String token, UUID key)
            throws Exception {
        return callPort("local.server.port", method, path, body, token, key);
    }

    private HttpResponse<String> callPort(
            String property, String method, String path, String body, String token, UUID key) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(origin(property) + path)).timeout(Duration.ofSeconds(8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key.toString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertDenial(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode problem = json(response);
        assertThat(problem.path("code").asString())
                .isEqualTo(status == 401 ? "AUTHENTICATION_REQUIRED" : "ACCESS_DENIED");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Idempotency-Replayed")).isEmpty();
    }
}
