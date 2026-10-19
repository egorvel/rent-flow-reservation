package com.rentflow.service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.rentflow.model.ReservationCancellationOutbox.FailureCode;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.support.ReservationPropertiesFixture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationCancellationOutboxRelayScheduledServiceTest {
    private ReservationCancellationOutboxRelayService relay;
    private ReservationCancellationOutboxRepository outboxes;
    private SimpleMeterRegistry metrics;
    private io.micrometer.observation.ObservationRegistry observations;

    @BeforeEach
    void setUp() {
        relay = mock(ReservationCancellationOutboxRelayService.class);
        outboxes = mock(ReservationCancellationOutboxRepository.class);
        metrics = new SimpleMeterRegistry();
        observations = io.micrometer.observation.ObservationRegistry.create();
        observations
                .observationConfig()
                .observationHandler(
                        new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(metrics));
        when(outboxes.countByPublishedAtIsNull()).thenReturn(0L);
        when(outboxes.databaseTime()).thenReturn(Instant.parse("2026-09-21T15:30:00Z"));
        when(outboxes.oldestPendingOccurredAt()).thenReturn(Optional.empty());
    }

    @Test
    void productionScheduleDefaultsToOneSecond() throws Exception {
        Method method = ReservationCancellationOutboxRelayScheduledService.class.getMethod("publish");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${reservation.cancellation.relay.fixed-delay}");
    }

    @Test
    void drainsPublishedEventsUntilTheQueueIsEmpty() {
        ReservationCancellationOutboxRelayService.Result published =
                new ReservationCancellationOutboxRelayService.Result(
                        ReservationCancellationOutboxRelayService.Status.PUBLISHED,
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        false);
        ReservationCancellationOutboxRelayService.Result empty = new ReservationCancellationOutboxRelayService.Result(
                ReservationCancellationOutboxRelayService.Status.EMPTY, null, 0, null, null, false);
        when(relay.publishOldest()).thenReturn(published, empty);
        ReservationCancellationOutboxRelayScheduledService scheduler = scheduler(100);

        scheduler.publish();

        verify(relay, times(2)).publishOldest();
        assertThat(metrics.counter("reservation.cancellation.outbox.publish.attempts", "outcome", "published")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void stopsAfterTheConfiguredEventLimit() {
        ReservationCancellationOutboxRelayService.Result published =
                new ReservationCancellationOutboxRelayService.Result(
                        ReservationCancellationOutboxRelayService.Status.PUBLISHED,
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        false);
        when(relay.publishOldest()).thenReturn(published);
        ReservationCancellationOutboxRelayScheduledService scheduler = scheduler(2);

        scheduler.publish();

        verify(relay, times(2)).publishOldest();
    }

    @Test
    void restoresInterruptOnlyAfterTheAttemptReturns() {
        ReservationCancellationOutboxRelayService.Result interrupted =
                new ReservationCancellationOutboxRelayService.Result(
                        ReservationCancellationOutboxRelayService.Status.INTERRUPTED,
                        UUID.randomUUID(),
                        1,
                        FailureCode.RELAY_INTERRUPTED,
                        Instant.parse("2026-09-21T15:30:01Z"),
                        false);
        when(relay.publishOldest()).thenReturn(interrupted);
        ReservationCancellationOutboxRelayScheduledService scheduler = scheduler(100);

        scheduler.publish();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(metrics.counter("reservation.cancellation.outbox.retries.scheduled")
                        .count())
                .isEqualTo(1);
        Thread.interrupted();
    }

    private ReservationCancellationOutboxRelayScheduledService scheduler(int maxEvents) {
        return new ReservationCancellationOutboxRelayScheduledService(
                relay,
                metrics,
                ReservationPropertiesFixture.runtimeWithRelayLimits(maxEvents, Duration.ofSeconds(5)),
                observations,
                java.time.Clock.systemUTC());
    }

    @Test
    void fatalWorkerFailureIsObservedAndRethrownWithoutClaimingSuccess() {
        AssertionError failure = new AssertionError("fatal worker failure");
        when(relay.publishOldest()).thenThrow(failure);
        ReservationCancellationOutboxRelayScheduledService worker = scheduler(100);
        org.assertj.core.api.Assertions.assertThatThrownBy(worker::publish).isSameAs(failure);
        assertThat(metrics.get("reservation.worker.run")
                        .tag("worker", "relay")
                        .tag("outcome", "error")
                        .timer()
                        .count())
                .isOne();
        assertThat(metrics.get("reservation.worker.last.success.timestamp")
                        .tag("worker", "relay")
                        .gauge()
                        .value())
                .isZero();
        assertThat(observations.getCurrentObservation()).isNull();
    }
}
