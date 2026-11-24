package com.rentflow.service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.rentflow.model.Reservation;
import com.rentflow.repository.ReservationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void authenticate() {
        com.rentflow.support.SignedJwtFixture.authenticateStaff();
    }

    @org.junit.jupiter.api.AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Mock
    private ReservationRepository repository;

    @Test
    void missingOperationsNeverMutate() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        when(repository.findForUpdateById(id)).thenReturn(Optional.empty());
        ReservationService service = new ReservationService(
                repository,
                org.mockito.Mockito.mock(CustomerIdentityService.class),
                new com.rentflow.service.ReservationAuthorizationPolicy());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.replace(
                        id,
                        new com.rentflow.model.ReservationReplacementCommand(
                                "NEW",
                                "ORDER",
                                LocalDate.now(),
                                LocalDate.now(),
                                com.rentflow.model.ReservationStatus.HELD)))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ReservationNotFoundException.class);
        verify(repository).findById(id);
        verify(repository, org.mockito.Mockito.times(2)).findForUpdateById(id);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void sortingHasAUniqueTieBreaker() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        new ReservationService(
                        repository,
                        org.mockito.Mockito.mock(CustomerIdentityService.class),
                        new com.rentflow.service.ReservationAuthorizationPolicy())
                .list(2, 15, ReservationSortField.START_DATE, Sort.Direction.DESC);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(15);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("startDate"), Sort.Order.asc("id")));
    }

    @Test
    void idSortDoesNotAddARedundantTieBreaker() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        new ReservationService(
                        repository,
                        org.mockito.Mockito.mock(CustomerIdentityService.class),
                        new com.rentflow.service.ReservationAuthorizationPolicy())
                .list(0, 20, ReservationSortField.ID, Sort.Direction.DESC);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("id")));
    }

    @Test
    void existingDeleteLoadsThenRemoves() {
        UUID id = UUID.randomUUID();
        Reservation reservation = reservation();
        when(repository.findForUpdateById(id)).thenReturn(Optional.of(reservation));
        new ReservationService(
                        repository,
                        org.mockito.Mockito.mock(CustomerIdentityService.class),
                        new com.rentflow.service.ReservationAuthorizationPolicy())
                .delete(id);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).findForUpdateById(id);
        order.verify(repository).delete(reservation);
        verifyNoMoreInteractions(repository);
    }

    private Reservation reservation() {
        return new Reservation(
                "DRILL-001",
                "CUSTOMER-1",
                "https://issuer.example",
                "ORDER-1",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 3));
    }
}
