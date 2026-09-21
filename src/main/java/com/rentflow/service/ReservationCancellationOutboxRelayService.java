package com.rentflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.ReservationCancellationFailureCode;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.repository.ReservationCancellationOutboxRepository;

@Service
public class ReservationCancellationOutboxRelayService {
    private final ReservationCancellationOutboxRepository outboxes;
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final String topic;
    private final Duration sendTimeout;
    private final Duration initialBackoff;
    private final double backoffMultiplier;
    private final Duration maxBackoff;
    private final double backoffJitter;

    public ReservationCancellationOutboxRelayService(
            ReservationCancellationOutboxRepository outboxes,
            KafkaTemplate<byte[], byte[]> kafka,
            @Value("${reservation.cancellation.topic:rentflow.reservation.cancelled.v1}") String topic,
            @Value("${reservation.cancellation.relay.send-timeout:50s}") Duration sendTimeout,
            @Value("${reservation.cancellation.retry.initial-backoff:1s}") Duration initialBackoff,
            @Value("${reservation.cancellation.retry.multiplier:2}") double backoffMultiplier,
            @Value("${reservation.cancellation.retry.max-backoff:5m}") Duration maxBackoff,
            @Value("${reservation.cancellation.retry.jitter:0.2}") double backoffJitter) {
        this.outboxes = outboxes;
        this.kafka = kafka;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
        this.maxBackoff = maxBackoff;
        this.backoffJitter = backoffJitter;
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

        try {
            CompletableFuture<SendResult<byte[], byte[]>> future = kafka.send(
                    topic,
                    outbox.getRecordKey().getBytes(StandardCharsets.UTF_8),
                    outbox.getPayload().getBytes(StandardCharsets.UTF_8));
            future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            return recordFailure(outbox, ReservationCancellationFailureCode.RELAY_INTERRUPTED, true);
        } catch (TimeoutException exception) {
            return recordFailure(outbox, ReservationCancellationFailureCode.KAFKA_SEND_TIMEOUT, false);
        } catch (ExecutionException | RuntimeException exception) {
            ReservationCancellationFailureCode code = isTimeout(exception)
                    ? ReservationCancellationFailureCode.KAFKA_SEND_TIMEOUT
                    : ReservationCancellationFailureCode.KAFKA_SEND_FAILED;
            return recordFailure(outbox, code, false);
        }

        boolean recovered = outbox.getLastFailureCode() != null;
        Instant publishedAt = outboxes.databaseTime();
        outbox.recordPublished(publishedAt);
        return Result.published(outbox.getEventId(), outbox.getAttemptCount(), recovered);
    }

    private Result recordFailure(
            ReservationCancellationOutbox outbox,
            ReservationCancellationFailureCode failureCode,
            boolean restoreInterrupt) {
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
        return new Result(status, outbox.getEventId(), outbox.getAttemptCount(), failureCode, nextAttemptAt, false);
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
            ReservationCancellationFailureCode failureCode,
            Instant nextAttemptAt,
            boolean recovered) {
        private static Result stopped(Status status) {
            return new Result(status, null, 0, null, null, false);
        }

        private static Result published(UUID eventId, int attemptCount, boolean recovered) {
            return new Result(Status.PUBLISHED, eventId, attemptCount, null, null, recovered);
        }
    }
}
