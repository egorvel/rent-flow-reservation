package com.rentflow.service.rest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.rentflow.service.InventoryGateway.ClaimResult;
import com.rentflow.service.InventoryServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestInventoryServiceTest {
    @Mock
    private InventoryHttpClient client;

    private RestInventoryService service;

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = RetryConfig.custom().maxAttempts(1).build();
        service = new RestInventoryService(
                client, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.of(retryConfig), new ObjectMapper());
    }

    @Test
    void sendsOneOrderedReservedBatchWithTheSameKey() {
        UUID key = UUID.randomUUID();
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ResponseEntity.noContent().build());

        ClaimResult result = service.claim(key, List.of("A", "B"));

        assertThat(result.type()).isEqualTo(ClaimResult.Type.CLAIMED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryHttpClient.InventoryStatusChangeRequest>> changes =
                ArgumentCaptor.forClass(List.class);
        verify(client).reserve(org.mockito.ArgumentMatchers.eq(key), changes.capture());
        assertThat(changes.getValue())
                .containsExactly(
                        new InventoryHttpClient.InventoryStatusChangeRequest("A", "RESERVED"),
                        new InventoryHttpClient.InventoryStatusChangeRequest("B", "RESERVED"));
    }

    @Test
    void mapsMixedLifecycleFailuresByOriginalIndexWithoutUpstreamMessages() {
        UUID key = UUID.randomUUID();
        String problem = """
                {"type":"urn:rentflow:problem:invalid-inventory-status-transition",
                 "title":"Invalid inventory status transition","status":409,
                 "detail":"raw top-level detail","instance":"/api/v1/inventory/status",
                 "code":"INVALID_INVENTORY_STATUS_TRANSITION","failedItems":[
                  {"index":0,"serialNumber":"A","status":"RESERVED","code":"INVENTORY_ITEM_NOT_FOUND","message":"raw missing detail"},
                  {"index":1,"serialNumber":"B","status":"RESERVED","code":"INVALID_INVENTORY_STATUS_TRANSITION","message":"raw lifecycle detail"}
                ]}
                """;
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenThrow(clientError(HttpStatus.CONFLICT, problem));

        ClaimResult result = service.claim(key, List.of("A", "B"));

        assertThat(result.type()).isEqualTo(ClaimResult.Type.UNAVAILABLE);
        assertThat(result.failures())
                .extracting(failure -> failure.code())
                .containsExactly("INVENTORY_ITEM_NOT_FOUND", "INVENTORY_ITEM_UNAVAILABLE");
        assertThat(result.failures())
                .extracting(failure -> failure.message())
                .doesNotContain("raw missing detail", "raw lifecycle detail");
    }

    @Test
    void mapsInventoryBusyWithoutLeakingTransportDetails() {
        UUID key = UUID.randomUUID();
        String problem = """
                {"type":"urn:rentflow:problem:idempotency-in-progress",
                 "title":"Idempotent request in progress","status":409,
                 "detail":"raw detail","instance":"/api/v1/inventory/status",
                 "code":"IDEMPOTENCY_IN_PROGRESS","violations":[]}
                """;
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenThrow(clientError(HttpStatus.CONFLICT, problem));

        ClaimResult result = service.claim(key, List.of("A"));

        assertThat(result).isEqualTo(ClaimResult.busy());
    }

    @Test
    void retryPredicateMatchesOnlyTransportAndGatewayStatuses() {
        InventoryRetryableExceptionPredicate predicate = new InventoryRetryableExceptionPredicate();

        assertThat(predicate.test(new ResourceAccessException("timeout"))).isTrue();
        assertThat(predicate.test(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(predicate.test(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(predicate.test(clientError(HttpStatus.CONFLICT, "{}"))).isFalse();
    }

    @Test
    void retriesResourceAccessFailureFiveTimes() {
        configureRetry(5);
        UUID key = UUID.randomUUID();
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.claim(key, List.of("A")))
                .isInstanceOf(InventoryServiceUnavailableException.class);

        verify(client, times(5)).reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void retriesSelectedGatewayFailuresFiveTimes(int statusCode) {
        configureRetry(5);
        UUID key = UUID.randomUUID();
        HttpServerErrorException failure = HttpServerErrorException.create(
                HttpStatus.valueOf(statusCode), "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.claim(key, List.of("A")))
                .isInstanceOf(InventoryServiceUnavailableException.class);

        verify(client, times(5)).reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotRetryOtherServerFailures() {
        configureRetry(5);
        UUID key = UUID.randomUUID();
        HttpServerErrorException failure = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(client.reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.claim(key, List.of("A")))
                .isInstanceOf(InventoryServiceUnavailableException.class);

        verify(client).reserve(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.any());
    }

    private void configureRetry(int maxAttempts) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ZERO)
                .retryOnException(new InventoryRetryableExceptionPredicate())
                .build();
        service = new RestInventoryService(
                client, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.of(retryConfig), new ObjectMapper());
    }

    private HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status, "", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
