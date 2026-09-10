package com.rentflow.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "RentFlow Reservation API",
                        version = "v1",
                        description =
                                "Reservation CRUD with atomic, idempotent batch creation and synchronous Inventory availability claims."),
        tags = @Tag(name = "Reservations", description = "Reservation resource operations."))
public class OpenApiConfig {}
