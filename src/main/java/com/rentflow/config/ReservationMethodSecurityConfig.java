package com.rentflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.observation.SecurityObservationSettings;

import com.rentflow.service.ReservationAuthorizationPolicy;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ReservationMethodSecurityConfig {
    @Bean
    ReservationAuthorizationPolicy reservationAuthorizationPolicy() {
        return new ReservationAuthorizationPolicy();
    }

    @Bean
    SecurityObservationSettings reservationSecurityObservationSettings() {
        // Use the bounded denial counter/diagnostic, without framework authorization detail spans.
        return SecurityObservationSettings.noObservations();
    }
}
