package com.rentflow.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ReservationSchedulingConfig {
    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(4).threadNamePrefix("reservation-worker-").build();
    }

    @Bean
    ThreadPoolTaskScheduler reservationMonitoringTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(4).threadNamePrefix("reservation-monitor-").build();
    }
}
