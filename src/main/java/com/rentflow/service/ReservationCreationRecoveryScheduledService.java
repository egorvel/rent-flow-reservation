package com.rentflow.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReservationCreationRecoveryScheduledService {
    private final ReservationCreationRecoveryService recoveryService;

    public ReservationCreationRecoveryScheduledService(ReservationCreationRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${reservation.creation.recovery.fixed-delay:30s}")
    public void recover() {
        recoveryService.recoverDue();
    }
}
