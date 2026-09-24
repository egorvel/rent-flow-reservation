package com.rentflow.service.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rentflow.model.ReservationCreationOutcome.Failure;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.InventoryGateway.ClaimResult;
import com.rentflow.service.InventoryGateway.ProtocolException;
import com.rentflow.service.InventoryGateway.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.databind.ObjectMapper;

@Service
public class RestInventoryService implements InventoryGateway {
    private static final String RESERVED = "RESERVED";

    private final InventoryHttpClient inventoryClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObjectMapper objectMapper;

    public RestInventoryService(
            InventoryHttpClient inventoryClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ObjectMapper objectMapper) {
        this.inventoryClient = inventoryClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        this.retry = retryRegistry.retry("inventory");
        this.objectMapper = objectMapper;
    }

    @Override
    public ClaimResult claim(UUID idempotencyKey, List<String> serialNumbers) {
        List<InventoryHttpClient.InventoryStatusChangeRequest> changes = serialNumbers.stream()
                .map(serialNumber -> new InventoryHttpClient.InventoryStatusChangeRequest(serialNumber, RESERVED))
                .toList();
        Supplier<ClaimResult> retriedCall = Retry.decorateSupplier(retry, () -> request(idempotencyKey, changes));
        Supplier<ClaimResult> guardedCall = CircuitBreaker.decorateSupplier(circuitBreaker, retriedCall);
        try {
            return guardedCall.get();
        } catch (HttpClientErrorException exception) {
            return decodeClientError(exception, serialNumbers);
        } catch (CallNotPermittedException | ResourceAccessException | HttpServerErrorException exception) {
            throw new ServiceUnavailableException(exception);
        } catch (UnexpectedInventoryResponseException exception) {
            throw new ProtocolException(exception);
        }
    }

    private ClaimResult request(UUID idempotencyKey, List<InventoryHttpClient.InventoryStatusChangeRequest> changes) {
        ResponseEntity<Void> response = inventoryClient.reserve(idempotencyKey, changes);
        if (response.getStatusCode().value() == HttpStatus.NO_CONTENT.value()) {
            return ClaimResult.claimed();
        }
        throw new UnexpectedInventoryResponseException(response.getStatusCode());
    }

    private ClaimResult decodeClientError(HttpClientErrorException exception, List<String> serialNumbers) {
        InventoryProblem problem;
        try {
            problem = objectMapper.readValue(exception.getResponseBodyAsByteArray(), InventoryProblem.class);
        } catch (RuntimeException exceptionDuringDecoding) {
            throw new ProtocolException(new UnexpectedInventoryResponseException(exceptionDuringDecoding));
        }

        int status = exception.getStatusCode().value();
        if (status == 409 && "IDEMPOTENCY_IN_PROGRESS".equals(problem.code())) {
            return ClaimResult.busy();
        }
        if (status == 422 && "IDEMPOTENCY_KEY_REUSED".equals(problem.code())) {
            return ClaimResult.terminal(ClaimResult.Type.KEY_REUSED, List.of());
        }
        if (status == 400 && "VALIDATION_FAILED".equals(problem.code())) {
            return ClaimResult.terminal(
                    ClaimResult.Type.INVALID_REFERENCE, invalidReferenceFailures(problem.violations(), serialNumbers));
        }
        if (status == 404 && "INVENTORY_ITEM_NOT_FOUND".equals(problem.code())) {
            return ClaimResult.terminal(
                    ClaimResult.Type.MISSING, businessFailures(problem.failedItems(), serialNumbers));
        }
        if (status == 409 && "INVALID_INVENTORY_STATUS_TRANSITION".equals(problem.code())) {
            return ClaimResult.terminal(
                    ClaimResult.Type.UNAVAILABLE, businessFailures(problem.failedItems(), serialNumbers));
        }
        throw new ProtocolException(new UnexpectedInventoryResponseException(exception.getStatusCode()));
    }

    private List<Failure> invalidReferenceFailures(List<InventoryViolation> violations, List<String> serialNumbers) {
        List<Failure> failures = new ArrayList<>();
        for (InventoryViolation violation : violations == null ? List.<InventoryViolation>of() : violations) {
            int index = parseIndex(violation.field());
            if (index >= 0 && index < serialNumbers.size() && violation.field().endsWith(".serialNumber")) {
                failures.add(new Failure(
                        index,
                        serialNumbers.get(index),
                        "INVALID_INVENTORY_REFERENCE",
                        "Inventory rejected the serial number."));
            }
        }
        if (failures.isEmpty()) {
            throw new ProtocolException(new UnexpectedInventoryResponseException(HttpStatus.BAD_REQUEST));
        }
        return failures;
    }

    private List<Failure> businessFailures(List<InventoryFailure> upstreamFailures, List<String> serialNumbers) {
        if (upstreamFailures == null || upstreamFailures.isEmpty()) {
            throw new ProtocolException(new UnexpectedInventoryResponseException(HttpStatus.CONFLICT));
        }
        List<Failure> failures = new ArrayList<>();
        for (InventoryFailure failure : upstreamFailures) {
            if (failure.index() < 0
                    || failure.index() >= serialNumbers.size()
                    || !serialNumbers.get(failure.index()).equals(failure.serialNumber())) {
                throw new ProtocolException(new UnexpectedInventoryResponseException(HttpStatus.CONFLICT));
            }
            boolean missing = "INVENTORY_ITEM_NOT_FOUND".equals(failure.code());
            boolean unavailable = "INVALID_INVENTORY_STATUS_TRANSITION".equals(failure.code());
            if (!missing && !unavailable) {
                throw new ProtocolException(new UnexpectedInventoryResponseException(HttpStatus.CONFLICT));
            }
            failures.add(new Failure(
                    failure.index(),
                    failure.serialNumber(),
                    missing ? "INVENTORY_ITEM_NOT_FOUND" : "INVENTORY_ITEM_UNAVAILABLE",
                    missing ? "Inventory item was not found." : "Inventory item is not available."));
        }
        return failures.stream()
                .sorted(java.util.Comparator.comparingInt(Failure::index))
                .toList();
    }

    private int parseIndex(String field) {
        if (field == null || !field.startsWith("[")) {
            return -1;
        }
        int end = field.indexOf(']');
        try {
            return end > 1 ? Integer.parseInt(field.substring(1, end)) : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryProblem(
            String code, List<InventoryFailure> failedItems, List<InventoryViolation> violations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryFailure(int index, String serialNumber, String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryViolation(String field) {}
}
