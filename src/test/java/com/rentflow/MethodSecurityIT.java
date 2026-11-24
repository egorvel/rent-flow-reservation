package com.rentflow;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rentflow.config.ReservationMethodSecurityConfig;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.CustomerIdentityService;
import com.rentflow.service.ReservationNotFoundException;
import com.rentflow.service.ReservationService;
import com.rentflow.support.SignedJwtFixture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MethodSecurityIT {
    @Test
    void enforcesActualServiceProxyWithoutServletRequestState() {
        ReservationRepository repository = mock(ReservationRepository.class);
        CustomerIdentityService identities = mock(CustomerIdentityService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationMethodSecurityConfig.class)
                .withBean(
                        ReservationService.class,
                        () -> new ReservationService(
                                repository, identities, new com.rentflow.service.ReservationAuthorizationPolicy()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReservationService service = context.getBean(ReservationService.class);
                    UUID missing = UUID.randomUUID();
                    try {
                        SecurityContextHolder.clearContext();
                        assertThatThrownBy(() -> service.get(missing)).isInstanceOf(AccessDeniedException.class);
                        assertThatThrownBy(() -> service.delete(missing)).isInstanceOf(AccessDeniedException.class);
                        verifyNoInteractions(repository, identities);
                        SignedJwtFixture.authenticateStaff();
                        assertThatThrownBy(() -> service.get(missing)).isInstanceOf(ReservationNotFoundException.class);
                        verify(repository).findById(missing);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
    }
}
