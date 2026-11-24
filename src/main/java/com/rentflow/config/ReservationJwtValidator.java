package com.rentflow.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;

public final class ReservationJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult INVALID =
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    private final OAuth2TokenValidator<Jwt> validators;

    public ReservationJwtValidator(String issuer, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        timestamps.setAllowEmptyNotBeforeClaim(true);
        validators = new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator("reservation-api"),
                new JwtTypeValidator("JWT"),
                new JwtClaimValidator<>("typ", "Bearer"::equals),
                new JwtClaimValidator<>(JwtClaimNames.SUB, ReservationJwtValidator::nonblank),
                new JwtClaimValidator<>(IdTokenClaimNames.AZP, ReservationJwtValidator::nonblank),
                // JwtIssuedAtValidator also rejects older tokens; our contract has only a future bound.
                new JwtClaimValidator<Instant>(
                        JwtClaimNames.IAT,
                        issued -> !issued.isAfter(clock.instant().plusSeconds(30))));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object audience = jwt.getClaims().get(JwtClaimNames.AUD);
        Object notBefore = jwt.getClaims().get(JwtClaimNames.NBF);
        // Preserve strict claim types and return validation failures before typed built-ins access them.
        if (!(jwt.getHeaders().get(JoseHeaderNames.TYP) instanceof String)
                || !(jwt.getClaims().get(JwtClaimNames.ISS) instanceof String)
                || !(jwt.getClaims().get(JwtClaimNames.IAT) instanceof Instant)
                || !(jwt.getClaims().get(JwtClaimNames.EXP) instanceof Instant)
                || notBefore != null && !(notBefore instanceof Instant)
                || !(audience instanceof String
                        || audience instanceof Collection<?> values
                                && values.stream().allMatch(ReservationJwtValidator::nonblank))
                || !roles(jwt.getClaims().get("resource_access"))) {
            return INVALID;
        }
        if (audience instanceof String value) {
            // Nimbus normally normalizes this already; retain scalar support for any Jwt caller.
            Jwt normalized = Jwt.withTokenValue(jwt.getTokenValue())
                    .headers(headers -> headers.putAll(jwt.getHeaders()))
                    .claims(claims -> claims.putAll(jwt.getClaims()))
                    .audience(List.of(value))
                    .build();
            return validators.validate(normalized);
        }
        return validators.validate(jwt);
    }

    private static boolean nonblank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private boolean roles(Object value) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof Map<?, ?> resources)) {
            return false;
        }
        Object reservation = resources.get("reservation-api");
        if (reservation == null) {
            return true;
        }
        return reservation instanceof Map<?, ?> entry
                && (entry.get("roles") == null
                        || entry.get("roles") instanceof Collection<?> roles
                                && roles.stream().allMatch(String.class::isInstance));
    }
}
