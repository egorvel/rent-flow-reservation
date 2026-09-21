package com.rentflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.repository.ReservationCancellationOutboxRepository;

@Service
public class ReservationCancellationOutboxCleanupService {
    private final ReservationCancellationOutboxRepository outboxes;

    public ReservationCancellationOutboxCleanupService(ReservationCancellationOutboxRepository outboxes) {
        this.outboxes = outboxes;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteChunk() {
        return outboxes.deletePublishedChunk();
    }

    @Transactional(readOnly = true)
    public long countBacklog() {
        return outboxes.countCleanupBacklog();
    }
}
