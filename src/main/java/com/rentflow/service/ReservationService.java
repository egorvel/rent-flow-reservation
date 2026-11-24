package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.CustomerIdentity;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationReplacementCommand;
import com.rentflow.repository.ReservationRepository;

@Service
public class ReservationService {
    private final ReservationRepository repository;
    private final CustomerIdentityService identities;
    private final ReservationAuthorizationPolicy policy;

    public ReservationService(
            ReservationRepository repository,
            CustomerIdentityService identities,
            ReservationAuthorizationPolicy policy) {
        this.identities = identities;
        this.policy = policy;
        this.repository = repository;
    }

    @org.springframework.security.access.prepost.PreAuthorize("@reservationAuthorizationPolicy.canRead()")
    @Transactional(readOnly = true)
    public Reservation get(UUID id) {
        CustomerIdentity customer = policy.canReadAny() ? null : identities.currentIdentity();
        return (customer == null
                        ? repository.findById(id)
                        : repository.findByIdAndCustomerIssuerAndCustomerId(id, customer.issuer(), customer.subject()))
                .orElseThrow(() -> new ReservationNotFoundException(id));
    }

    @org.springframework.security.access.prepost.PreAuthorize("@reservationAuthorizationPolicy.canList()")
    @Transactional(readOnly = true)
    public Page<Reservation> list(int page, int size, ReservationSortField field, Sort.Direction direction) {
        CustomerIdentity customer = policy.canListAny() ? null : identities.currentIdentity();
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, field.property()));
        if (field != ReservationSortField.ID) {
            orders.add(Sort.Order.asc("id"));
        }
        PageRequest request = PageRequest.of(page, size, Sort.by(orders));
        return customer == null
                ? repository.findAll(request)
                : repository.findAllByCustomerIssuerAndCustomerId(customer.issuer(), customer.subject(), request);
    }

    @org.springframework.security.access.prepost.PreAuthorize("@reservationAuthorizationPolicy.canManage()")
    @Transactional
    public Reservation replace(UUID id, ReservationReplacementCommand replacement) {
        Reservation reservation =
                repository.findForUpdateById(id).orElseThrow(() -> new ReservationNotFoundException(id));
        reservation.replaceDetails(replacement);
        return reservation;
    }

    @org.springframework.security.access.prepost.PreAuthorize("@reservationAuthorizationPolicy.canManage()")
    @Transactional
    public void delete(UUID id) {
        Reservation reservation =
                repository.findForUpdateById(id).orElseThrow(() -> new ReservationNotFoundException(id));
        repository.delete(reservation);
    }
}
