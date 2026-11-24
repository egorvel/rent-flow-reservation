package com.rentflow.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

import com.rentflow.service.rest.InventoryHttpClient;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "inventory", types = InventoryHttpClient.class)
public class InventoryHttpClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryHttpClientConfig.class);

    @Bean
    OAuth2AuthorizedClientManager inventoryAuthorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients,
            Clock clock,
            MeterRegistry metrics) {
        OAuth2AccessTokenResponseHttpMessageConverter converter = new OAuth2AccessTokenResponseHttpMessageConverter();
        DefaultMapOAuth2AccessTokenResponseConverter delegate = new DefaultMapOAuth2AccessTokenResponseConverter();
        converter.setAccessTokenResponseConverter(parameters -> {
            OAuth2AccessTokenResponse response = delegate.convert(parameters);
            Object expiry = parameters.get("expires_in");
            if (!OAuth2AccessToken.TokenType.BEARER.equals(
                            response.getAccessToken().getTokenType())
                    || !(expiry instanceof Number seconds)
                    || seconds.longValue() <= 0) {
                throw safeFailure("token_response");
            }
            return response;
        });
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(converter))
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (_, response) -> {
                    int status = response.getStatusCode().value();
                    throw safeFailure(
                            status == 429 || status >= 500
                                    ? "token_unavailable"
                                    : status >= 400 ? "credentials" : "token_response");
                })
                .build());

        ClientCredentialsOAuth2AuthorizedClientProvider provider =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setClock(clock);
        provider.setClockSkew(Duration.ofSeconds(30));
        provider.setAccessTokenResponseClient(request -> {
            try {
                return tokenClient.getTokenResponse(request);
            } catch (RuntimeException failure) {
                String reason = tokenFailureReason(failure);
                metrics.counter("reservation.security.outbound.failures", "dependency", "inventory", "reason", reason)
                        .increment();
                LOGGER.atWarn()
                        .addKeyValue("dependency", "inventory")
                        .addKeyValue("reason", reason)
                        .log("Inventory token acquisition failed");
                // Discard provider-controlled descriptions, response bodies and exception causes.
                throw safeFailure(reason);
            }
        });
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    private static OAuth2AuthorizationException safeFailure(String reason) {
        return new OAuth2AuthorizationException(new OAuth2Error(reason));
    }

    private static String tokenFailureReason(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return "token_unavailable";
            }
            if (cause instanceof OAuth2AuthorizationException oauth
                    && List.of("credentials", "token_response", "token_unavailable")
                            .contains(oauth.getError().getErrorCode())) {
                return oauth.getError().getErrorCode();
            }
        }
        return "token_response";
    }

    @Bean
    RestClientHttpServiceGroupConfigurer authenticatedInventoryClient(
            OAuth2AuthorizedClientManager manager, OAuth2AuthorizedClientService clients) {
        OAuth2ClientHttpRequestInterceptor oauth = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth.setClientRegistrationIdResolver(_ -> "inventory");
        oauth.setPrincipalResolver(new RequestAttributePrincipalResolver());
        return groups -> groups.filterByName("inventory")
                .forEachClient((_, builder) -> builder.defaultStatusHandler(
                                HttpStatusCode::is5xxServerError, (_, response) -> {
                                    throw org.springframework.web.client.HttpServerErrorException.create(
                                            response.getStatusCode(),
                                            "Inventory dependency error",
                                            HttpHeaders.EMPTY,
                                            new byte[0],
                                            java.nio.charset.StandardCharsets.UTF_8);
                                })
                        .defaultRequest(request ->
                                request.attributes(RequestAttributePrincipalResolver.principal("reservation-service")))
                        .requestInterceptor((request, body, execution) -> {
                            org.springframework.http.client.ClientHttpResponse response =
                                    oauth.intercept(request, body, execution);
                            if (response.getStatusCode().value() == 401) {
                                clients.removeAuthorizedClient("inventory", "reservation-service");
                            }
                            return response;
                        })
                        .defaultStatusHandler(
                                status -> status.value() == 401 || status.value() == 403, (_, response) -> {
                                    throw HttpClientErrorException.create(
                                            response.getStatusCode(),
                                            "Inventory access rejected",
                                            HttpHeaders.EMPTY,
                                            new byte[0],
                                            java.nio.charset.StandardCharsets.UTF_8);
                                }));
    }

    static JdkClientHttpRequestFactory requestFactory(Duration connect, Duration read) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(read);
        return factory;
    }
}
