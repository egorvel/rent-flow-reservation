package com.rentflow.config;

import java.time.Clock;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.kafka.core.KafkaAdmin;

import io.micrometer.observation.ObservationPredicate;

@Configuration(proxyBeanMethods = false)
public class ReservationObservabilityConfig {
    @Bean
    static BeanPostProcessor kafkaClusterIdOverride(Environment environment) {
        String clusterId = environment.getProperty("reservation.kafka.cluster-id");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof KafkaAdmin admin && clusterId != null && !clusterId.isBlank()) {
                    admin.setClusterId(clusterId);
                }
                return bean;
            }
        };
    }

    @Bean
    Clock reservationClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObservationPredicate excludeManagementObservations() {
        return (_, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                String path = request.getCarrier().getRequestURI();
                return !path.equals("/actuator")
                        && !path.startsWith("/actuator/")
                        && !path.equals("/livez")
                        && !path.equals("/readyz");
            }
            return true;
        };
    }
}
