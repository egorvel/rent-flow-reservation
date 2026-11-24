package com.rentflow.model;

import java.util.Objects;

/** Verified issuer and subject; neither value is normalized or supplied by API callers. */
public record CustomerIdentity(String issuer, String subject) {
    public CustomerIdentity {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(subject);
        if (issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("Customer identity must be nonblank");
        }
    }
}
