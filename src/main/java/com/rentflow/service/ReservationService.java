package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Reservation;
import com.rentflow.repository.ReservationRepository;

@Service
public class ReservationService {
    private final ReservationRepository repository;

    public ReservationService(ReservationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Reservation get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Reservation> list(int page, int size, ReservationSortField field, Sort.Direction direction) {
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, field.property()));
        if (field != ReservationSortField.ID) {
            orders.add(Sort.Order.asc("id"));
        }
        return repository.findAll(PageRequest.of(page, size, Sort.by(orders)));
    }

    @Transactional
    public Reservation replace(UUID id, Reservation replacement) {
        Reservation reservation = repository.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
        reservation.replaceDetails(replacement);
        return reservation;
    }

    @Transactional
    public void delete(UUID id) {
        Reservation reservation = repository.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
        repository.delete(reservation);
    }
}
