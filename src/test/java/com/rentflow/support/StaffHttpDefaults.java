package com.rentflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestConfiguration(proxyBeanMethods = false)
public class StaffHttpDefaults {
    @Bean
    MockMvcBuilderCustomizer signedStaffRequests() {
        return builder ->
                builder.defaultRequest(get("/").header("Authorization", "Bearer " + SignedJwtFixture.SHARED.staff()));
    }
}
