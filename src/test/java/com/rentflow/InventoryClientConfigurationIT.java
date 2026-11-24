package com.rentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import com.rentflow.config.InventoryClientConfigurationValidator;

import static org.assertj.core.api.Assertions.*;

class InventoryClientConfigurationIT {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(InventoryClientConfigurationValidator.class)
            .withPropertyValues(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://127.0.0.1:1/realms/test",
                    "spring.security.oauth2.client.provider.keycloak.token-uri=https://127.0.0.1:1/realms/test/protocol/openid-connect/token",
                    "spring.security.oauth2.client.registration.inventory.provider=keycloak",
                    "spring.security.oauth2.client.registration.inventory.client-id=reservation-service",
                    "spring.security.oauth2.client.registration.inventory.authorization-grant-type=client_credentials",
                    "spring.security.oauth2.client.registration.inventory.client-authentication-method=client_secret_basic",
                    "spring.http.serviceclient.inventory.base-url=https://127.0.0.1:2");

    @Test
    void bootCreatesTheRegistrationAndRunsTheValidatorWithoutProviderAccess() {
        runner.withPropertyValues(
                        "spring.security.oauth2.client.registration.inventory.client-secret=ephemeral-test-only")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(ClientRegistrationRepository.class)
                            .hasSingleBean(InventoryClientConfigurationValidator.class)
                            .hasBean("clientRegistrationRepository")
                            .doesNotHaveBean("inventoryRegistration");
                    ClientRegistration registration =
                            context.getBean(ClientRegistrationRepository.class).findByRegistrationId("inventory");
                    assertThat(registration.getClientId()).isEqualTo("reservation-service");
                    assertThat(registration.getAuthorizationGrantType())
                            .isEqualTo(AuthorizationGrantType.CLIENT_CREDENTIALS);
                    assertThat(registration.getClientAuthenticationMethod())
                            .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                    assertThat(registration.getProviderDetails().getTokenUri())
                            .isEqualTo("https://127.0.0.1:1/realms/test/protocol/openid-connect/token");
                });
    }

    @Test
    void startupFailsWhenTheBoundRegistrationHasNoSecret() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Invalid Inventory OAuth client configuration"));
    }

    @Test
    void startupFailsWhenTheBoundTokenEndpointDoesNotMatchTheRealm() {
        runner.withPropertyValues(
                        "spring.security.oauth2.client.registration.inventory.client-secret=ephemeral-test-only",
                        "spring.security.oauth2.client.provider.keycloak.token-uri=https://different.example/token")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("Invalid Inventory OAuth client configuration"));
    }
}
