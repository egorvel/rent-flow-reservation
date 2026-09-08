package com.rentflow.service;

import java.time.Duration;

public interface ReservationCreationSettings {
    Duration leaseDuration();

    int recoveryBatchSize();

    Duration recoveryInitialBackoff();

    Duration recoveryMaxBackoff();

    double recoveryJitterFactor();

    Duration cleanupRuntimeBudget();
}
