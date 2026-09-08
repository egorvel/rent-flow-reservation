package com.rentflow.service;

import java.time.Instant;

public record ReservationCreationHttpResponse(
        int status, Object body, String code, boolean replayed, Instant expiresAt) {}
