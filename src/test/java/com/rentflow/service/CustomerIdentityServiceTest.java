package com.rentflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerIdentityServiceTest {
    @Test
    void rejectsMissingAuthenticatedIdentityInsteadOfUsingAFallback() {
        SecurityContextHolder.clearContext();
        CustomerIdentityService identities = new CustomerIdentityService();
        assertThatThrownBy(identities::currentIdentity).isInstanceOf(AccessDeniedException.class);
    }
}
