package com.rentflow.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "RentFlow Reservation API",
                        version = "v1",
                        description =
                                "Reservation CRUD with atomic, idempotent batch creation and synchronous Inventory availability claims."),
        tags = @Tag(name = "Reservations", description = "Reservation resource operations."))
public class OpenApiConfig {
    @Bean
    OpenApiCustomizer reservationSecurityDocumentation(Environment environment) {
        return api -> {
            String issuer = environment.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            api.getComponents()
                    .addSecuritySchemes(
                            "bearerJwt",
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.HTTP)
                                    .scheme("bearer")
                                    .bearerFormat("JWT"));
            api.getComponents()
                    .addSecuritySchemes(
                            "reservationOidc",
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.OAUTH2)
                                    .flows(new OAuthFlows()
                                            .authorizationCode(new OAuthFlow()
                                                    .authorizationUrl(issuer + "/protocol/openid-connect/auth")
                                                    .tokenUrl(issuer + "/protocol/openid-connect/token")
                                                    .scopes(new Scopes()
                                                            .addString("openid", "OpenID Connect login")))));
            api.getPaths()
                    .forEach((_, item) -> item.readOperationsMap().forEach((_, operation) -> {
                        operation.addSecurityItem(new SecurityRequirement().addList("bearerJwt"));
                        operation.addSecurityItem(new SecurityRequirement().addList("reservationOidc"));
                        for (String code : java.util.List.of("401", "403")) {
                            operation
                                    .getResponses()
                                    .addApiResponse(
                                            code,
                                            new ApiResponse()
                                                    .description(
                                                            code.equals("401")
                                                                    ? "Valid bearer authentication required."
                                                                    : "Operation not permitted.")
                                                    .addHeaderObject(
                                                            "WWW-Authenticate",
                                                            new io.swagger.v3.oas.models.headers.Header()
                                                                    .schema(new Schema<String>()
                                                                            .type("string")
                                                                            .typesItem("string")))
                                                    .content(
                                                            new Content()
                                                                    .addMediaType(
                                                                            "application/problem+json",
                                                                            new MediaType()
                                                                                    .schema(
                                                                                            new Schema<>()
                                                                                                    .$ref(
                                                                                                            "#/components/schemas/ProblemResponse")))));
                        }
                    }));
        };
    }
}
