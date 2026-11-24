package com.rentflow.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AuthenticatedIdempotencyTest {
    @Test
    void preservesTheSpecifiedLengthPrefixedSha256AndUuidVectors() {
        String scope = IdempotencyFingerprint.callerScope("https://issuer.example/realm", "customer-a");
        UUID external = UUID.fromString("00000000-0000-4000-8000-000000000001");
        assertThat(scope).isEqualTo("p1:c324cdcac2f2505cd227b5517145bd798ee63a229bac8917405cd7b7891bc990");
        assertThat(IdempotencyFingerprint.lockId(scope, external)).isEqualTo(1509751071979604309L);
        UUID inventory = IdempotencyFingerprint.inventoryKey(scope, external);
        assertThat(inventory).isEqualTo(UUID.fromString("ed6166f3-30af-4209-b6a3-3ecaa83c6864"));
        assertThat(inventory.version()).isEqualTo(4);
        assertThat(inventory.variant()).isEqualTo(2);
        assertThat(IdempotencyKeyParser.parse(java.util.List.of(inventory.toString())))
                .isEqualTo(inventory);
    }

    @Test
    void separatesSubjectsIssuersAmbiguousTuplesAndExternalKeys() {
        String first = IdempotencyFingerprint.callerScope("https://issuer.example", "a");
        String second = IdempotencyFingerprint.callerScope("https://issuer.example", "b");
        String third = IdempotencyFingerprint.callerScope("https://other.example", "a");
        assertThat(java.util.Set.of(first, second, third)).hasSize(3);
        assertThat(IdempotencyFingerprint.callerScope("ab", "c"))
                .isNotEqualTo(IdempotencyFingerprint.callerScope("a", "bc"));
        UUID key = UUID.randomUUID();
        assertThat(IdempotencyFingerprint.lockId(first, key)).isNotEqualTo(IdempotencyFingerprint.lockId(second, key));
        assertThat(IdempotencyFingerprint.inventoryKey(first, key))
                .isNotEqualTo(IdempotencyFingerprint.inventoryKey(second, key));
        assertThat(IdempotencyFingerprint.inventoryKey(first, key))
                .isNotEqualTo(IdempotencyFingerprint.inventoryKey(third, key));
        assertThat(IdempotencyFingerprint.inventoryKey(first, key))
                .isNotEqualTo(IdempotencyFingerprint.inventoryKey(first, UUID.randomUUID()));
    }
}
