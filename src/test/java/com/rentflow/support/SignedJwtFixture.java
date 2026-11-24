package com.rentflow.support;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.rentflow.converter.ReservationAuthorityConverter;
import com.sun.net.httpserver.HttpServer;

public final class SignedJwtFixture implements AutoCloseable {
    public static final SignedJwtFixture SHARED = new SignedJwtFixture();
    public static final List<String> STAFF = List.of(
            "reservation:create",
            "reservation:read:any",
            "reservation:list:any",
            "reservation:cancel:any",
            "reservation:manage");
    public static final List<String> CUSTOMER =
            List.of("reservation:create", "reservation:read", "reservation:list", "reservation:cancel");
    private final HttpServer server;
    private final String issuerHost;
    private RSAKey key;
    private RSAKey previousKey;
    private boolean available = true;
    private int fetches;
    private int tokenRequests;
    private final java.util.Queue<TokenReply> tokenReplies = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.List<String> tokenAuthorization = new java.util.ArrayList<>();
    private final java.util.List<String> tokenBodies = new java.util.ArrayList<>();
    private long tokenDelayMillis;

    private final String machineToken = "fixture-service-" + java.util.UUID.randomUUID();

    public SignedJwtFixture() {
        this("127.0.0.1", "127.0.0.1");
    }

    public SignedJwtFixture(String bindHost, String issuerHost) {
        this.issuerHost = issuerHost;
        try {
            key = new RSAKeyGenerator(2048)
                    .keyID(java.util.UUID.randomUUID().toString())
                    .generate();
            server = HttpServer.create(new InetSocketAddress(bindHost, 0), 0);
            server.createContext("/realms/test/protocol/openid-connect/certs", exchange -> {
                synchronized (this) {
                    fetches++;
                    byte[] body = (available ? publicJwks() : "unavailable").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(available ? 200 : 503, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/realms/test/protocol/openid-connect/token", exchange -> {
                TokenReply reply;
                long delay;
                synchronized (this) {
                    tokenRequests++;
                    tokenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    tokenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    reply = tokenReplies.poll();
                    delay = tokenDelayMillis;
                }
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = (reply == null
                                ? "{\"access_token\":\"" + machineToken
                                        + "\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                                : reply.body())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", issuer() + "/redirect-target");
                exchange.sendResponseHeaders(reply == null ? 200 : reply.status(), body.length);
                try {
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot start signing fixture", error);
        }
    }

    public String issuer() {
        return "http://" + issuerHost + ":" + server.getAddress().getPort() + "/realms/test";
    }

    public record TokenReply(int status, String body) {}

    public synchronized void reply(int status, String body) {
        tokenReplies.add(new TokenReply(status, body));
    }

    public synchronized void tokenDelay(long millis) {
        tokenDelayMillis = millis;
    }

    public synchronized void resetTokenRequests() {
        tokenReplies.clear();
        tokenAuthorization.clear();
        tokenBodies.clear();
        tokenRequests = 0;
        tokenDelayMillis = 0;
    }

    public synchronized java.util.List<String> tokenAuthorization() {
        return java.util.List.copyOf(tokenAuthorization);
    }

    public synchronized java.util.List<String> tokenBodies() {
        return java.util.List.copyOf(tokenBodies);
    }

    public String tokenUri() {
        return issuer() + "/protocol/openid-connect/token";
    }

    public synchronized int tokenRequests() {
        return tokenRequests;
    }

    public String jwks() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    public Map<String, Object> claims(String subject, String client, List<String> permissions) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(JwtClaimNames.ISS, issuer());
        claims.put(JwtClaimNames.SUB, subject);
        claims.put(IdTokenClaimNames.AZP, client);
        claims.put(JwtClaimNames.AUD, List.of("reservation-api"));
        claims.put(JwtClaimNames.IAT, Instant.now().getEpochSecond());
        claims.put(JwtClaimNames.EXP, Instant.now().plusSeconds(300).getEpochSecond());
        claims.put("typ", "Bearer");
        claims.put("resource_access", Map.of("reservation-api", Map.of("roles", permissions)));
        return claims;
    }

    public String token(String subject, String client, List<String> permissions) {
        return token(claims(subject, client, permissions), JWSAlgorithm.RS256, "JWT");
    }

    public String staff() {
        return token("staff", "reservation-swagger", STAFF);
    }

    public String token(Map<String, Object> claims, JWSAlgorithm algorithm, String type) {
        try {
            JWSObject object = new JWSObject(
                    new JWSHeader.Builder(algorithm)
                            .type(type == null ? null : new JOSEObjectType(type))
                            .keyID(key.getKeyID())
                            .build(),
                    new Payload(JSONObjectUtils.toJSONString(claims)));
            object.sign(new RSASSASigner(key));
            return object.serialize();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot sign test credential", error);
        }
    }

    public static void authenticateStaff() {
        Map<String, Object> claims = SHARED.claims("staff", "reservation-swagger", STAFF);
        claims.put(JwtClaimNames.IAT, Instant.now());
        claims.put(JwtClaimNames.EXP, Instant.now().plusSeconds(300));
        Jwt jwt = Jwt.withTokenValue("in-memory-service-test")
                .header(JoseHeaderNames.ALG, "RS256")
                .claims(values -> values.putAll(claims))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, new ReservationAuthorityConverter().convert(jwt)));
    }

    public synchronized void available(boolean available) {
        this.available = available;
    }

    public synchronized int fetches() {
        return fetches;
    }

    private String publicJwks() {
        return (previousKey == null
                        ? new JWKSet(key.toPublicJWK())
                        : new JWKSet(List.of(key.toPublicJWK(), previousKey.toPublicJWK())))
                .toString();
    }

    public synchronized void rotate() {
        try {
            previousKey = key;
            key = new RSAKeyGenerator(2048)
                    .keyID(java.util.UUID.randomUUID().toString())
                    .generate();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
