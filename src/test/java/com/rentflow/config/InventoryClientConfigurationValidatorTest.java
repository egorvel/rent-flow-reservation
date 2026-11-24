package com.rentflow.config;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryClientConfigurationValidatorTest {
    @Test
    void validatesTheResolvedRegistrationWithoutConnectingToTheProvider() {
        assertThatCode(() -> validate(registration(builder -> {}), settings())).doesNotThrowAnyException();
        MockEnvironment local = settings()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://127.0.0.1:1/realms/test")
                .withProperty("spring.http.serviceclient.inventory.base-url", "http://127.0.0.1:2");
        local.setActiveProfiles("test");
        assertThatCode(() -> validate(
                        registration(builder ->
                                builder.tokenUri("http://127.0.0.1:1/realms/test/protocol/openid-connect/token")),
                        local))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRegistrationAndInvalidMachineClientSettings() {
        assertThatThrownBy(() -> validate(null, settings())).isInstanceOf(IllegalArgumentException.class);
        List<Consumer<ClientRegistration.Builder>> invalid = List.of(
                builder -> builder.clientId("pricing-service"),
                builder -> builder.clientSecret(null),
                builder -> builder.clientSecret(" "),
                builder -> builder.scope("openid"),
                builder -> builder.authorizationGrantType(new AuthorizationGrantType("password")),
                builder -> builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE),
                builder -> builder.tokenUri("https://different.example/token"));
        for (Consumer<ClientRegistration.Builder> override : invalid) {
            assertThatThrownBy(() -> validate(registration(override), settings()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingIssuerOrDestination() {
        for (String property : List.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "spring.http.serviceclient.inventory.base-url")) {
            MockEnvironment missing = settings();
            ((org.springframework.core.env.MapPropertySource)
                            missing.getPropertySources().get("mockProperties"))
                    .getSource()
                    .remove(property);
            assertThatThrownBy(() -> validate(registration(builder -> {}), missing))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsUnsafeDestinationsAndInsecureTokenTransportOutsideLocalProfiles() {
        for (String destination : List.of(
                "http://inventory.example",
                "https://user:password@inventory.example",
                "https://inventory.example?target=other",
                "https://inventory.example#fragment",
                "/relative",
                " ")) {
            assertThatThrownBy(() -> validate(
                            registration(builder -> {}),
                            settings().withProperty("spring.http.serviceclient.inventory.base-url", destination)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        MockEnvironment insecure = settings()
                .withProperty(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://identity.example/realms/test");
        assertThatThrownBy(() -> validate(
                        registration(builder ->
                                builder.tokenUri("http://identity.example/realms/test/protocol/openid-connect/token")),
                        insecure))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void validate(ClientRegistration registration, MockEnvironment environment) {
        ClientRegistrationRepository registrations = mock(ClientRegistrationRepository.class);
        when(registrations.findByRegistrationId("inventory")).thenReturn(registration);
        new InventoryClientConfigurationValidator(registrations, environment);
    }

    private ClientRegistration registration(Consumer<ClientRegistration.Builder> override) {
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId("inventory")
                .clientId("reservation-service")
                .clientSecret("ephemeral-test-only")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .tokenUri("https://127.0.0.1:1/realms/test/protocol/openid-connect/token");
        override.accept(builder);
        return builder.build();
    }

    private MockEnvironment settings() {
        return new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://127.0.0.1:1/realms/test")
                .withProperty("spring.http.serviceclient.inventory.base-url", "https://127.0.0.1:2");
    }
}
