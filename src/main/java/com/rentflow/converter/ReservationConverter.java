package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.CreateReservationsRequest;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationStatus;

@Component
public class ReservationConverter {
    public ReservationCreationCommand toCommand(CreateReservationsRequest request) {
        return new ReservationCreationCommand(
                request.customerId(),
                request.orderId(),
                request.items().stream()
                        .map(item -> new ReservationCreationCommand.Item(
                                item.serialNumber(), item.startDate(), item.endDate()))
                        .toList());
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
