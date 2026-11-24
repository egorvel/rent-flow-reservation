package com.rentflow.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

import com.rentflow.converter.ReservationAuthorityConverter;
import com.rentflow.service.ReservationAuthorizationPolicy;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ReservationSecurityConfig {
    @Bean
    JwtDecoder reservationJwtDecoder(Environment environment, Clock clock) {
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String jwks = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        validateUrls(issuer, jwks, environment.matchesProfiles("local", "test"));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .validateType(false)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory))
                .build();
        MappedJwtClaimSetConverter claimConverter = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            // Check before default conversion can synthesize iat from exp.
            // Nimbus has already parsed numeric JWT timestamps into Date values at this boundary.
            if (!(claims.get(JwtClaimNames.IAT) instanceof java.util.Date)
                    || !(claims.get(JwtClaimNames.EXP) instanceof java.util.Date)
                    || claims.get(JwtClaimNames.NBF) != null
                            && !(claims.get(JwtClaimNames.NBF) instanceof java.util.Date)) {
                throw new BadJwtException("Invalid timestamp claims");
            }
            return claimConverter.convert(claims);
        });
        decoder.setJwtValidator(new ReservationJwtValidator(issuer, clock));
        return decoder;
    }

    static void validateUrls(String issuer, String jwks, boolean local) {
        URI uri = URI.create(issuer);
        boolean allowed = uri.isAbsolute()
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && ("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                && !issuer.endsWith("/")
                && jwks.equals(issuer + "/protocol/openid-connect/certs");
        if (!allowed) {
            throw new IllegalArgumentException("Invalid Reservation issuer/JWKS configuration");
        }
    }

    @Bean
    ReservationSecurityResponses reservationSecurityResponses(ObjectMapper mapper, MeterRegistry metrics) {
        return new ReservationSecurityResponses(mapper, metrics);
    }

    @Bean
    @Order(1)
    SecurityFilterChain managementSecurity(
            HttpSecurity http, Environment environment, ReservationSecurityResponses responses) {
        http.securityMatcher(request -> management(request, environment));
        stateless(http);
        http.authorizeHttpRequests(rules -> rules.requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/prometheus")
                .permitAll()
                .anyRequest()
                .denyAll());
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, _) -> responses.write(request, response, 403, "management", null))
                .accessDeniedHandler(
                        (request, response, _) -> responses.write(request, response, 403, "management", null)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            Environment environment,
            ReservationSecurityResponses responses,
            ReservationAuthorizationPolicy policy,
            JwtDecoder decoder) {
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        stateless(http);
        http.authorizeHttpRequests(rules -> {
            rules.requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll();
            if (environment.matchesProfiles("local")
                    && environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                    && environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false)) {
                rules.requestMatchers(
                                HttpMethod.GET,
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/swagger-config")
                        .permitAll();
            }
            rules.requestMatchers(HttpMethod.GET, "/api/v1/reservations")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canList(authentication.get())))
                    .requestMatchers(HttpMethod.GET, "/api/v1/reservations/*")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canRead(authentication.get())))
                    .requestMatchers(HttpMethod.POST, "/api/v1/reservations")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canCreate(authentication.get())))
                    .requestMatchers(HttpMethod.POST, "/api/v1/reservations/*/cancel")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canCancel(authentication.get())))
                    .requestMatchers(HttpMethod.PUT, "/api/v1/reservations/*")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canManage(authentication.get())))
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/reservations/*")
                    .access((authentication, _) -> new AuthorizationDecision(policy.canManage(authentication.get())))
                    .anyRequest()
                    .denyAll();
        });
        org.springframework.security.web.AuthenticationEntryPoint entryPoint =
                (request, response, _) -> responses.write(
                        request,
                        response,
                        401,
                        "application",
                        request.getHeader("Authorization") == null ? "Bearer" : "Bearer error=\"invalid_token\"");
        org.springframework.security.web.access.AccessDeniedHandler denied = (request, response, _) ->
                responses.write(request, response, 403, "application", "Bearer error=\"insufficient_scope\"");
        http.exceptionHandling(
                errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ReservationAuthorityConverter());
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        http.oauth2ResourceServer(resource -> resource.bearerTokenResolver(request -> {
                    List<String> headers = Collections.list(request.getHeaders("Authorization"));
                    if (headers.size() > 1
                            || !headers.isEmpty() && !headers.getFirst().matches("(?i)Bearer [A-Za-z0-9\\-._~+/]+=*")) {
                        throw new InvalidBearerTokenException(BearerTokenErrorCodes.INVALID_TOKEN);
                    }
                    return resolver.resolve(request);
                })
                .authenticationEntryPoint(entryPoint)
                .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                        builder -> builder.authorizationServer(issuer).tlsClientCertificateBoundAccessTokens(false)))
                .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }

    private static boolean management(HttpServletRequest request, Environment environment) {
        Integer port = environment.getProperty("local.management.port", Integer.class);
        if (port == null) {
            port = environment.getProperty("management.server.port", Integer.class, 8087);
        }
        return request.getLocalPort() == port;
    }

    private static void stateless(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }
}
