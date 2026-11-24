package com.rentflow.model;

import java.io.Serializable;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record ReservationCreationRequestId(
        @Column(name = "caller_scope", nullable = false, length = 67)
        String callerScope,

        @Column(name = "idempotency_key", nullable = false) UUID idempotencyKey)
        implements Serializable {}
