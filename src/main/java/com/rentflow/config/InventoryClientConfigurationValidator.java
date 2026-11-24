package com.rentflow.config;

import java.net.URI;

import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

@Component
public class InventoryClientConfigurationValidator {
    public InventoryClientConfigurationValidator(ClientRegistrationRepository registrations, Environment environment) {
        ClientRegistration registration = registrations.findByRegistrationId("inventory");
        String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        if (registration == null
                || !"reservation-service".equals(registration.getClientId())
                || registration.getClientSecret().isBlank()
                || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType())
                || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())
                || !registration.getScopes().isEmpty()
                || !(issuer + "/protocol/openid-connect/token")
                        .equals(registration.getProviderDetails().getTokenUri())) {
            throw new IllegalArgumentException("Invalid Inventory OAuth client configuration");
        }
        boolean local = environment.matchesProfiles("local", "test");
        validateDestination(registration.getProviderDetails().getTokenUri(), local);
        validateDestination(environment.getRequiredProperty("spring.http.serviceclient.inventory.base-url"), local);
    }

    static void validateDestination(String destination, boolean local) {
        URI uri = URI.create(destination);
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid Inventory destination configuration");
        }
    }
}
