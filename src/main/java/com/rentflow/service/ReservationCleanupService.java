package com.rentflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;

@Service
public class ReservationCleanupService {
    private final ReservationCreationRequestRepository creationRequests;
    private final ReservationCancellationOutboxRepository cancellationOutboxes;

    public ReservationCleanupService(
            ReservationCreationRequestRepository creationRequests,
            ReservationCancellationOutboxRepository cancellationOutboxes) {
        this.creationRequests = creationRequests;
        this.cancellationOutboxes = cancellationOutboxes;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredCreationChunk() {
        return creationRequests.deleteExpiredChunk();
    }

    @Transactional(readOnly = true)
    public long countExpiredCreationRequests() {
        return creationRequests.countExpired();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deletePublishedCancellationChunk() {
        return cancellationOutboxes.deletePublishedChunk();
    }

    @Transactional(readOnly = true)
    public long countCancellationCleanupBacklog() {
        return cancellationOutboxes.countCleanupBacklog();
    }
}
