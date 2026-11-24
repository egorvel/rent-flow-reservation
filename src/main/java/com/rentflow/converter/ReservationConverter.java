package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.CreateReservationsRequest;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCreationCommand;
import com.rentflow.model.ReservationReplacementCommand;
import com.rentflow.model.ReservationStatus;

@Component
public class ReservationConverter {
    public ReservationCreationCommand toCommand(CreateReservationsRequest request) {
        return new ReservationCreationCommand(
                request.orderId(),
                request.items().stream()
                        .map(item -> new ReservationCreationCommand.Item(
                                item.serialNumber(), item.startDate(), item.endDate()))
                        .toList());
    }

    public ReservationReplacementCommand toCommand(ReservationDTO request) {
        return new ReservationReplacementCommand(
                request.serialNumber(),
                request.orderId(),
                request.startDate(),
                request.endDate(),
                ReservationStatus.valueOf(request.status()));
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
                reservation.getHoldExpiresAt(),
                reservation.getStatus().name());
    }
}
