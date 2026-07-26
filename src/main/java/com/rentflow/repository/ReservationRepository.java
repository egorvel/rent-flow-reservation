package com.rentflow.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rentflow.model.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {}
