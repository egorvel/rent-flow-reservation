package com.rentflow.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.rentflow.support.SignedJwtFixture;

import static org.assertj.core.api.Assertions.*;

class ReservationSecurityConfigTest {
    @Test
    void createsDecoderWithoutNetworkAndUsesCachedKeysDuringOutageThenRefreshes() {
        try (SignedJwtFixture fixture = new SignedJwtFixture()) {
            fixture.available(false);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("test");
            environment.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", fixture.issuer());
            environment.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", fixture.jwks());
            JwtDecoder decoder = new ReservationSecurityConfig().reservationJwtDecoder(environment, Clock.systemUTC());
            assertThat(fixture.fetches()).isZero();
            fixture.available(true);
            String known = fixture.staff();
            assertThat(decoder.decode(known).getSubject()).isEqualTo("staff");
            int warmed = fixture.fetches();
            fixture.available(false);
            assertThat(decoder.decode(known).getSubject()).isEqualTo("staff");
            assertThat(fixture.fetches()).isEqualTo(warmed);
            fixture.rotate();
            String unknown = fixture.staff();
            assertThatThrownBy(() -> decoder.decode(unknown)).isInstanceOf(JwtException.class);
            fixture.available(true);
            assertThat(decoder.decode(unknown).getSubject()).isEqualTo("staff");
            // During supported overlapping-key rotation both old and new credentials remain valid.
            assertThat(decoder.decode(known).getSubject()).isEqualTo("staff");
        }
    }

    @Test
    void requiresExplicitTrustedMatchingUrlsAndDisallowsDeploymentHttp() {
        assertThatThrownBy(() ->
                        new ReservationSecurityConfig().reservationJwtDecoder(new MockEnvironment(), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        for (String invalid : List.of(
                "http://auth.example/realm",
                "https://user@auth.example/realm",
                "https://auth.example/realm?issuer=x",
                "https://auth.example/realm#fragment",
                "/realm")) {
            assertThatThrownBy(() -> ReservationSecurityConfig.validateUrls(
                            invalid, invalid + "/protocol/openid-connect/certs", false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ReservationSecurityConfig.validateUrls(
                        "https://auth.example/realm", "https://other.example/keys", false))
                .isInstanceOf(IllegalArgumentException.class);
        ReservationSecurityConfig.validateUrls(
                "https://auth.example/realm", "https://auth.example/realm/protocol/openid-connect/certs", false);
    }

    @Test
    void enforcesExactThirtySecondTimeBoundariesWithAnInjectedClock() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        ReservationJwtValidator validator =
                new ReservationJwtValidator("https://issuer.example/realm", Clock.fixed(now, ZoneOffset.UTC));
        for (int delta : List.of(29, 30, 31)) {
            Jwt future = jwt(now.plusSeconds(delta), now.plusSeconds(300), now.plusSeconds(delta));
            assertThat(validator.validate(future).hasErrors()).isEqualTo(delta > 30);
            Jwt expired = jwt(now.minusSeconds(300), now.minusSeconds(delta), null);
            assertThat(validator.validate(expired).hasErrors()).isEqualTo(delta > 30);
        }
    }

    private Jwt jwt(Instant issued, Instant expiry, Instant notBefore) {
        return Jwt.withTokenValue("unit-test")
                .header(JoseHeaderNames.ALG, "RS256")
                .header(JoseHeaderNames.TYP, "JWT")
                .issuer("https://issuer.example/realm")
                .subject("subject")
                .audience(List.of("reservation-api"))
                .issuedAt(issued)
                .expiresAt(expiry)
                .claims(claims -> {
                    claims.put(IdTokenClaimNames.AZP, "reservation-swagger");
                    claims.put("typ", "Bearer");
                    if (notBefore != null) {
                        claims.put(JwtClaimNames.NBF, notBefore);
                    }
                })
                .build();
    }
}
