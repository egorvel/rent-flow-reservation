package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.CreateReservationRequest;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationStatus;

@Component
public class ReservationConverter {
    public Reservation toModel(CreateReservationRequest request) {
        return new Reservation(
                request.serialNumber(),
                request.customerId(),
                request.orderId(),
                request.startDate(),
                request.endDate());
    }

    public Reservation toModel(ReservationDTO request) {
        Reservation reservation = new Reservation(
                request.serialNumber(),
                request.customerId(),
                request.orderId(),
                request.startDate(),
                request.endDate());
        reservation.changeStatus(ReservationStatus.valueOf(request.status()));
        return reservation;
    }

    public ReservationDTO toResponse(Reservation reservation) {
        return new ReservationDTO(
                reservation.getId(),
                reservation.getSerialNumber(),
                reservation.getCustomerId(),
                reservation.getOrderId(),
                reservation.getStartDate(),
                reservation.getEndDate(),
                reservation.getTimestamp(),
                reservation.getStatus().name());
    }
}
