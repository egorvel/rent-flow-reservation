package com.rentflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.repository.ReservationCreationRequestRepository;

@Service
public class ReservationCreationCleanupService {
    private final ReservationCreationRequestRepository creationRequests;

    public ReservationCreationCleanupService(ReservationCreationRequestRepository creationRequests) {
        this.creationRequests = creationRequests;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteChunk() {
        return creationRequests.deleteExpiredChunk();
    }

    @Transactional(readOnly = true)
    public long countExpired() {
        return creationRequests.countExpired();
    }
}
