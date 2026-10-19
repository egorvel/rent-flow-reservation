package com.rentflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationCancellationOutbox.FailureCode;
import com.rentflow.repository.ReservationCancellationOutboxRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

@Service
public class ReservationCancellationOutboxRelayService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCancellationOutboxRelayService.class);
    private final ReservationCancellationOutboxRepository outboxes;
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final String topic;
    private final Duration sendTimeout;
    private final Duration initialBackoff;
    private final double backoffMultiplier;
    private final Duration maxBackoff;
    private final double backoffJitter;
    private final Tracer tracer;
    private final OpenTelemetry openTelemetry;
    private final ObservationRegistry observations;
    private final MeterRegistry metrics;

    public ReservationCancellationOutboxRelayService(
            ReservationCancellationOutboxRepository outboxes,
            KafkaTemplate<byte[], byte[]> kafka,
            ReservationRuntimeSettings settings,
            Tracer tracer,
            OpenTelemetry openTelemetry,
            ObservationRegistry observations,
            MeterRegistry metrics) {
        ReservationRuntimeSettings.Cancellation cancellation = settings.cancellation();
        this.outboxes = outboxes;
        this.kafka = kafka;
        this.topic = cancellation.topic();
        this.sendTimeout = cancellation.relay().sendTimeout();
        this.initialBackoff = cancellation.retry().initialBackoff();
        this.backoffMultiplier = cancellation.retry().multiplier();
        this.maxBackoff = cancellation.retry().maxBackoff();
        this.backoffJitter = cancellation.retry().jitter();
        this.tracer = tracer;
        this.openTelemetry = openTelemetry;
        this.observations = observations;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result publishOldest() {
        if (!outboxes.tryRelayLock()) {
            return Result.stopped(Status.LOCK_BUSY);
        }
        Optional<ReservationCancellationOutbox> selected =
                outboxes.findFirstByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc();
        if (selected.isEmpty()) {
            return Result.stopped(Status.EMPTY);
        }

        ReservationCancellationOutbox outbox = selected.get();
        Instant databaseTime = outboxes.databaseTime();
        if (outbox.getNextAttemptAt().isAfter(databaseTime)) {
            return Result.stopped(Status.BACKING_OFF);
        }

        Span worker = tracer.currentSpan();
        TraceContext origin = originContext(outbox);
        Span attempt = startPublicationSpan(outbox, origin, worker);
        return sendAndRecordOutcome(outbox, attempt, origin);
    }

    private Span startPublicationSpan(ReservationCancellationOutbox outbox, TraceContext origin, Span worker) {
        Span.Builder builder = tracer.spanBuilder().setNoParent().name("reservation.cancellation.outbox.publish");
        if (origin != null) {
            builder.addLink(new Link(origin));
        }
        if (worker != null
                && (origin == null
                        || !worker.context().traceId().equals(origin.traceId())
                        || !worker.context().spanId().equals(origin.spanId()))) {
            builder.addLink(new Link(worker));
        }
        Span attempt = builder.tag("eventId", outbox.getEventId().toString())
                .tag("attemptNumber", outbox.getAttemptCount() + 1L)
                .start();
        if (origin != null) {
            attempt.tag("originTraceId", origin.traceId());
        }
        return attempt;
    }

    private Result sendAndRecordOutcome(ReservationCancellationOutbox outbox, Span attempt, TraceContext origin) {
        Observation.Scope previous = observations.getCurrentObservationScope();
        observations.setCurrentObservationScope(null);
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "acknowledged";
        Throwable sendFailure = null;
        FailureCode failureCode = null;
        boolean interrupted = false;
        try (Tracer.SpanInScope _ = tracer.withSpan(attempt)) {
            sendAndAwaitAcknowledgement(outbox);
        } catch (InterruptedException exception) {
            outcome = "interrupted";
            attempt.error(exception);
            sendFailure = exception;
            failureCode = FailureCode.RELAY_INTERRUPTED;
            interrupted = true;
        } catch (TimeoutException exception) {
            outcome = "timeout";
            attempt.error(exception);
            sendFailure = exception;
            failureCode = FailureCode.KAFKA_SEND_TIMEOUT;
        } catch (ExecutionException | RuntimeException exception) {
            FailureCode code = isTimeout(exception) ? FailureCode.KAFKA_SEND_TIMEOUT : FailureCode.KAFKA_SEND_FAILED;
            outcome = code == FailureCode.KAFKA_SEND_TIMEOUT ? "timeout" : "failed";
            attempt.error(exception);
            sendFailure = exception;
            failureCode = code;
        } catch (Error failure) {
            outcome = "failed";
            attempt.error(failure);
            throw failure;
        } finally {
            attempt.tag("outcome", outcome).end();
            observations.setCurrentObservationScope(previous);
            recordSendDuration(sample, outcome);
        }

        if (sendFailure != null) {
            return recordFailure(outbox, failureCode, interrupted, sendFailure);
        }
        return recordPublished(outbox, attempt, origin);
    }

    private void sendAndAwaitAcknowledgement(ReservationCancellationOutbox outbox)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<SendResult<byte[], byte[]>> future = kafka.send(
                topic,
                outbox.getRecordKey().getBytes(StandardCharsets.UTF_8),
                outbox.getPayload().getBytes(StandardCharsets.UTF_8));
        future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordSendDuration(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("reservation.cancellation.outbox.publish.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(120))
                .register(metrics));
    }

    private Result recordPublished(ReservationCancellationOutbox outbox, Span attempt, TraceContext origin) {
        boolean recovered = outbox.getLastFailureCode() != null;
        Instant publishedAt = outboxes.databaseTime();
        outbox.recordPublished(publishedAt);
        recordPublicationAfterCommit(outbox, attempt, origin, recovered);
        return Result.published(outbox.getEventId(), outbox.getAttemptCount(), recovered);
    }

    private void recordPublicationAfterCommit(
            ReservationCancellationOutbox outbox, Span attempt, TraceContext origin, boolean recovered) {
        int attemptCount = outbox.getAttemptCount();
        Duration delay = Duration.between(outbox.getOccurredAt(), outbox.getPublishedAt());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try (Tracer.SpanInScope _ = tracer.withSpan(attempt)) {
                        logCommittedPublication(outbox.getEventId(), attemptCount, recovered, origin);
                        recordPublicationDelay(delay);
                    } catch (RuntimeException failure) {
                        LOGGER.warn("Committed publication telemetry could not be recorded.", failure);
                    }
                }
            });
        }
    }

    private void logCommittedPublication(UUID eventId, int attemptCount, boolean recovered, TraceContext origin) {
        org.slf4j.spi.LoggingEventBuilder log = LOGGER.atInfo()
                .addKeyValue("eventId", eventId)
                .addKeyValue("attemptCount", attemptCount)
                .addKeyValue("outcome", "published")
                .addKeyValue("recovered", recovered);
        if (origin != null) {
            log.addKeyValue("originTraceId", origin.traceId());
        }
        log.log("Reservation cancellation publication committed.");
    }

    private void recordPublicationDelay(Duration delay) {
        if (!delay.isNegative()) {
            Timer.builder("reservation.cancellation.outbox.publication.delay")
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofDays(7))
                    .register(metrics)
                    .record(delay);
        } else {
            LOGGER.warn("Cancellation publication delay has a future origin timestamp.");
        }
    }

    private TraceContext originContext(ReservationCancellationOutbox outbox) {
        String parent = outbox.getTraceparent();
        String state = outbox.getTracestate();
        if (parent == null || parent.length() > 55 || state != null && state.length() > 512) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put("traceparent", parent);
        if (state != null) {
            carrier.put("tracestate", state);
        }
        try {
            Context extracted = openTelemetry
                    .getPropagators()
                    .getTextMapPropagator()
                    .extract(Context.root(), carrier, new TextMapGetter<Map<String, String>>() {
                        @Override
                        public Iterable<String> keys(Map<String, String> headers) {
                            return headers.keySet();
                        }

                        @Override
                        public String get(Map<String, String> headers, String key) {
                            return headers.get(key);
                        }
                    });
            io.opentelemetry.api.trace.SpanContext context =
                    io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext();
            return context.isValid()
                    ? tracer.traceContextBuilder()
                            .traceId(context.getTraceId())
                            .spanId(context.getSpanId())
                            .sampled(context.isSampled())
                            .build()
                    : null;
        } catch (RuntimeException failure) {
            LOGGER.warn("Stored cancellation origin context could not be extracted.", failure);
            return null;
        }
    }

    private Result recordFailure(
            ReservationCancellationOutbox outbox,
            FailureCode failureCode,
            boolean restoreInterrupt,
            Throwable failure) {
        Instant failedAt = outboxes.databaseTime();
        int failedAttempt = outbox.getAttemptCount() + 1;
        Duration delay = retryDelay(
                initialBackoff,
                backoffMultiplier,
                maxBackoff,
                backoffJitter,
                failedAttempt,
                ThreadLocalRandom.current().nextDouble());
        Instant nextAttemptAt = failedAt.plus(delay);
        outbox.recordFailure(failedAt, failureCode, nextAttemptAt);
        Status status = restoreInterrupt ? Status.INTERRUPTED : Status.FAILED;
        return new Result(
                status, outbox.getEventId(), outbox.getAttemptCount(), failureCode, nextAttemptAt, false, failure);
    }

    static Duration retryDelay(
            Duration initialBackoff,
            double multiplier,
            Duration maximumBackoff,
            double jitter,
            int failedAttempt,
            double unitRandom) {
        int exponent = Math.max(0, failedAttempt - 1);
        double unbounded = initialBackoff.toMillis() * Math.pow(multiplier, exponent);
        double nominal = Math.min(maximumBackoff.toMillis(), unbounded);
        double factor = 1.0 - jitter + (2.0 * jitter * unitRandom);
        long delayMillis = Math.max(1L, Math.round(nominal * factor));
        return Duration.ofMillis(delayMillis);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof org.apache.kafka.common.errors.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public enum Status {
        PUBLISHED,
        EMPTY,
        LOCK_BUSY,
        BACKING_OFF,
        FAILED,
        INTERRUPTED
    }

    public record Result(
            Status status,
            UUID eventId,
            int attemptCount,
            FailureCode failureCode,
            Instant nextAttemptAt,
            boolean recovered,
            Throwable failure) {
        public Result(
                Status status,
                UUID eventId,
                int attemptCount,
                FailureCode failureCode,
                Instant nextAttemptAt,
                boolean recovered) {
            this(status, eventId, attemptCount, failureCode, nextAttemptAt, recovered, null);
        }

        private static Result stopped(Status status) {
            return new Result(status, null, 0, null, null, false);
        }

        private static Result published(UUID eventId, int attemptCount, boolean recovered) {
            return new Result(Status.PUBLISHED, eventId, attemptCount, null, null, recovered);
        }
    }
}
