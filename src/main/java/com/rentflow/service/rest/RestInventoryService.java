package com.rentflow.service.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import static com.rentflow.service.ReservationCreationOutcomes.IDEMPOTENCY_IN_PROGRESS;
import static com.rentflow.service.ReservationCreationOutcomes.IDEMPOTENCY_KEY_REUSED;
import static com.rentflow.service.ReservationCreationOutcomes.INVALID_INVENTORY_REFERENCE;
import static com.rentflow.service.ReservationCreationOutcomes.INVENTORY_ITEM_NOT_FOUND;
import static com.rentflow.service.ReservationCreationOutcomes.INVENTORY_ITEM_UNAVAILABLE;
import static com.rentflow.service.ReservationCreationOutcomes.VALIDATION_FAILED;

@Service
public class RestInventoryService implements InventoryGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestInventoryService.class);
    private static final String RESERVED = "RESERVED";
    private static final String INVALID_INVENTORY_STATUS_TRANSITION = "INVALID_INVENTORY_STATUS_TRANSITION";

    private final InventoryHttpClient inventoryClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;
    private final MeterRegistry metrics;

    public RestInventoryService(
            InventoryHttpClient inventoryClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ObjectMapper objectMapper,
            ObservationRegistry observations,
            MeterRegistry metrics) {
        this.metrics = metrics;
        this.inventoryClient = inventoryClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        this.retry = retryRegistry.retry("inventory");
        this.objectMapper = objectMapper;
        this.observations = observations;
        retry.getEventPublisher().onRetry(event -> {
            Observation current = observations.getCurrentObservation();
            if (current != null
                    && "reservation.inventory.claim".equals(current.getContext().getName())) {
                current.event(Observation.Event.of("inventory.retry"));
            }
            LOGGER.atWarn()
                    .addKeyValue("dependency", "inventory")
                    .addKeyValue("failedAttempt", event.getNumberOfRetryAttempts())
                    .addKeyValue("waitMillis", event.getWaitInterval().toMillis())
                    .addKeyValue(
                            "failureCode",
                            event.getLastThrowable() instanceof ResourceAccessException
                                    ? "resource_access"
                                    : "upstream_5xx")
                    .setCause(event.getLastThrowable())
                    .log("Inventory claim retry scheduled.");
        });
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State target = event.getStateTransition().getToState();
            org.slf4j.spi.LoggingEventBuilder log =
                    target == CircuitBreaker.State.OPEN || target == CircuitBreaker.State.FORCED_OPEN
                            ? LOGGER.atWarn()
                            : LOGGER.atInfo();
            log.addKeyValue("dependency", "inventory")
                    .addKeyValue("previousState", event.getStateTransition().getFromState())
                    .addKeyValue("newState", target)
                    .log("Inventory circuit state changed.");
        });
    }

    @Override
    public ClaimResult claim(UUID idempotencyKey, List<String> serialNumbers) {
        List<InventoryHttpClient.InventoryStatusChangeRequest> changes = serialNumbers.stream()
                .map(serialNumber -> new InventoryHttpClient.InventoryStatusChangeRequest(serialNumber, RESERVED))
                .toList();
        Supplier<ClaimResult> retriedCall = Retry.decorateSupplier(retry, () -> request(idempotencyKey, changes));
        Supplier<ClaimResult> guardedCall = CircuitBreaker.decorateSupplier(circuitBreaker, retriedCall);
        Observation observation = Observation.createNotStarted("reservation.inventory.claim", observations)
                .lowCardinalityKeyValue("dependency", "inventory")
                .lowCardinalityKeyValue("operation", "claim")
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            ClaimResult result;
            try {
                result = guardedCall.get();
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() == 401
                        || exception.getStatusCode().value() == 403) {
                    String reason = exception.getStatusCode().value() == 401
                            ? "upstream_unauthenticated"
                            : "upstream_forbidden";
                    metrics.counter(
                                    "reservation.security.outbound.failures",
                                    "dependency",
                                    "inventory",
                                    "reason",
                                    reason)
                            .increment();
                    LOGGER.atWarn()
                            .addKeyValue("dependency", "inventory")
                            .addKeyValue("reason", reason)
                            .log("Inventory access rejected");
                    throw new ProtocolException();
                }
                result = decodeClientError(exception, serialNumbers);
            }
            observation.lowCardinalityKeyValue(
                    "outcome",
                    switch (result.type()) {
                        case CLAIMED -> "claimed";
                        case BUSY -> "busy";
                        case MISSING -> "missing";
                        case UNAVAILABLE -> "unavailable_items";
                        case INVALID_REFERENCE -> "invalid_reference";
                        case KEY_REUSED -> "key_reused";
                    });
            return result;
        } catch (OAuth2AuthorizationException exception) {
            boolean unavailable =
                    "token_unavailable".equals(exception.getError().getErrorCode());
            RuntimeException safe = unavailable ? new ServiceUnavailableException() : new ProtocolException();
            observation
                    .lowCardinalityKeyValue("outcome", unavailable ? "unavailable" : "protocol_error")
                    .error(safe);
            throw safe;
        } catch (CallNotPermittedException exception) {
            failure(observation, "short_circuited", exception, false);
            throw new ServiceUnavailableException(exception);
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            failure(observation, "unavailable", exception, true);
            throw new ServiceUnavailableException(exception);
        } catch (UnexpectedInventoryResponseException exception) {
            failure(observation, "protocol_error", exception, true);
            throw new ProtocolException(exception);
        } catch (ProtocolException exception) {
            // Authentication failures already emitted their bounded reason category above.
            // Their cause-free public exception must not produce a second stack-trace diagnostic.
            failure(observation, "protocol_error", exception, exception.getCause() != null);
            throw exception;
        } catch (RuntimeException | Error exception) {
            failure(observation, "error", exception, true);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    private void failure(Observation observation, String outcome, Throwable cause, boolean log) {
        observation.lowCardinalityKeyValue("outcome", outcome).error(cause);
        if (log) {
            try (Observation.Scope ignored = observation.openScope()) {
                LOGGER.atWarn()
                        .addKeyValue("dependency", "inventory")
                        .addKeyValue("outcome", outcome)
                        .setCause(cause)
                        .log("Inventory claim failed.");
            }
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
            throw new ProtocolException(
                    new UnexpectedInventoryResponseException(org.springframework.http.HttpStatus.BAD_GATEWAY));
        }

        int status = exception.getStatusCode().value();
        if (status == 409 && IDEMPOTENCY_IN_PROGRESS.equals(problem.code())) {
            return ClaimResult.busy();
        }
        if (status == 422 && IDEMPOTENCY_KEY_REUSED.equals(problem.code())) {
            return ClaimResult.terminal(ClaimResult.Type.KEY_REUSED, List.of());
        }
        if (status == 400 && VALIDATION_FAILED.equals(problem.code())) {
            return ClaimResult.terminal(
                    ClaimResult.Type.INVALID_REFERENCE, invalidReferenceFailures(problem.violations(), serialNumbers));
        }
        if (status == 404 && INVENTORY_ITEM_NOT_FOUND.equals(problem.code())) {
            return ClaimResult.terminal(
                    ClaimResult.Type.MISSING, businessFailures(problem.failedItems(), serialNumbers));
        }
        if (status == 409 && INVALID_INVENTORY_STATUS_TRANSITION.equals(problem.code())) {
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
                        INVALID_INVENTORY_REFERENCE,
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
            boolean missing = INVENTORY_ITEM_NOT_FOUND.equals(failure.code());
            boolean unavailable = INVALID_INVENTORY_STATUS_TRANSITION.equals(failure.code());
            if (!missing && !unavailable) {
                throw new ProtocolException(new UnexpectedInventoryResponseException(HttpStatus.CONFLICT));
            }
            failures.add(new Failure(
                    failure.index(),
                    failure.serialNumber(),
                    missing ? INVENTORY_ITEM_NOT_FOUND : INVENTORY_ITEM_UNAVAILABLE,
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
