package com.rentflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCreationFailure;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.InventoryGateway.ClaimResult;
import com.rentflow.service.InventoryProtocolException;
import com.rentflow.service.InventoryServiceUnavailableException;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.service.ReservationCreationCleanupService;
import com.rentflow.support.PostgresIntegrationTest;

import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationIT extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/reservations";
    private static final String CREATE = """
        {"serialNumber":"DRILL-001","customerId":"CUSTOMER-001","orderId":"ORDER-001","startDate":"2026-10-01","endDate":"2026-10-03"}
        """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ReservationRepository repository;

    @Autowired
    private ReservationCreationRequestRepository creationRequestRepository;

    @Autowired
    private ReservationCancellationOutboxRepository cancellationOutboxRepository;

    @Autowired
    private ReservationCancellationService cancellationService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationCreationCleanupService creationCleanup;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    @BeforeEach
    void clearReservations() {
        cancellationOutboxRepository.deleteAllInBatch();
        creationRequestRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
        reset(inventoryGateway);
        when(inventoryGateway.claim(any(), any())).thenReturn(ClaimResult.claimed());
    }

    @Test
    void createsHeldAndReturnsExactlyThePersistedRepresentation() throws Exception {
        Instant before = Instant.now().minusMillis(1);
        JsonNode created = create(CREATE);
        UUID id = UUID.fromString(created.path("id").asString());
        assertThat(created.propertyNames())
                .containsExactlyInAnyOrder(
                        "id",
                        "serialNumber",
                        "customerId",
                        "orderId",
                        "startDate",
                        "endDate",
                        "timestamp",
                        "holdExpiresAt",
                        "status");
        assertThat(created.path("status").asString()).isEqualTo("HELD");
        Instant timestamp = Instant.parse(created.path("timestamp").asString());
        assertThat(timestamp).isBetween(before, Instant.now());
        assertThat(Instant.parse(created.path("holdExpiresAt").asString())).isEqualTo(timestamp.plusSeconds(600));
        assertThat(read(id.toString())).isEqualTo(created);
        assertThat(repository.findById(id).orElseThrow().getTimestamp().getNano() % 1000)
                .isZero();
    }

    @Test
    void oneSuccessfulBatchUsesOneDatabaseCreationTimeAndConfiguredDeadline() throws Exception {
        String request = """
                {"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[
                  {"serialNumber":"BATCH-001","startDate":"2026-10-01","endDate":"2026-10-03"},
                  {"serialNumber":"BATCH-002","startDate":"2026-10-01","endDate":"2026-10-03"}
                ]}
                """;

        JsonNode created = mapper.readTree(mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());

        assertThat(created).hasSize(2);
        Instant timestamp = Instant.parse(created.get(0).path("timestamp").asString());
        Instant holdExpiresAt =
                Instant.parse(created.get(0).path("holdExpiresAt").asString());
        assertThat(holdExpiresAt).isEqualTo(timestamp.plusSeconds(600));
        assertThat(created.get(1).path("timestamp")).isEqualTo(created.get(0).path("timestamp"));
        assertThat(created.get(1).path("holdExpiresAt"))
                .isEqualTo(created.get(0).path("holdExpiresAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONFIRMED"})
    void cancelsEligibleReservationAndCommitsOneOutboxEvent(String initialStatus) throws Exception {
        Reservation reservation = new Reservation(
                "CANCEL-001", "CUSTOMER-001", "ORDER-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        reservation.changeStatus(ReservationStatus.valueOf(initialStatus));
        Reservation saved = repository.saveAndFlush(reservation);

        mvc.perform(post(PATH + "/" + saved.getId() + "/cancel"))
                .andExpect(status().isNoContent())
                .andExpect(content().bytes(new byte[0]));

        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancellationOutboxRepository.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getRecordKey()).isEqualTo("CANCEL-001");
            assertThat(outbox.getPayload()).contains("\"eventType\":\"ReservationCancelled\"");
            assertThat(outbox.getPayload()).doesNotContain("reservationId");
        });
        verify(inventoryGateway, never()).claim(any(), any());
    }

    @Test
    void repeatedCancellationIsAnEmptyNoOpWithoutAnotherEvent() throws Exception {
        Reservation reservation = repository.saveAndFlush(new Reservation(
                "CANCEL-NOOP", "CUSTOMER-001", "ORDER-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));

        for (int request = 0; request < 2; request++) {
            mvc.perform(post(PATH + "/" + reservation.getId() + "/cancel"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().bytes(new byte[0]));
        }

        assertThat(cancellationOutboxRepository.count()).isOne();
    }

    @Test
    void cancellationRejectsMissingAndMalformedIdentifiersWithoutWrites() throws Exception {
        UUID missing = UUID.randomUUID();

        mvc.perform(post(PATH + "/" + missing + "/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(post(PATH + "/not-a-uuid/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(cancellationOutboxRepository.count()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void concurrentCancellationCreatesOneLogicalEvent() throws Exception {
        Reservation reservation = repository.saveAndFlush(new Reservation(
                "CANCEL-RACE", "CUSTOMER-001", "ORDER-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        int requests = 8;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int request = 0; request < requests; request++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    cancellationService.cancel(reservation.getId());
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(repository.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancellationOutboxRepository.count()).isOne();
    }

    @Test
    void storesAndReplaysAnActiveReservationConflict() throws Exception {
        create(CREATE);
        UUID key = UUID.randomUUID();
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(CREATE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RESERVATION_EXISTS"))
                .andExpect(jsonPath("$.failedItems[0].index").value(0))
                .andExpect(header().string("Idempotency-Replayed", "false"));
        Reservation existing = repository.findAll().getFirst();
        existing.changeStatus(ReservationStatus.CANCELLED);
        repository.saveAndFlush(existing);
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(CREATE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RESERVATION_EXISTS"))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(repository.count()).isEqualTo(1);
        verify(inventoryGateway, times(1)).claim(any(), any());
    }

    @Test
    void ignoresAnOutdatedConfirmedReservation() throws Exception {
        Reservation outdated = new Reservation(
                "DRILL-001", "old-customer", "old-order", LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2));
        outdated.changeStatus(ReservationStatus.CONFIRMED);
        repository.saveAndFlush(outdated);

        create(CREATE);

        assertThat(repository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONFIRMED"})
    void treatsAnActiveReservationEndingTodayAsAConflict(String reservationStatus) throws Exception {
        LocalDate today = creationRequestRepository.databaseUtcDate();
        Reservation existing = new Reservation("DRILL-001", "old-customer", "old-order", today.minusDays(2), today);
        existing.changeStatus(ReservationStatus.valueOf(reservationStatus));
        repository.saveAndFlush(existing);

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchFor("DRILL-001", today.plusDays(1), today.plusDays(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RESERVATION_EXISTS"));

        verify(inventoryGateway, times(0)).claim(any(), any());
    }

    @Test
    void allowsACancelledFutureReservationAndKeepsSerialsCaseSensitive() throws Exception {
        LocalDate today = creationRequestRepository.databaseUtcDate();
        Reservation cancelled =
                new Reservation("DRILL-001", "old-customer", "old-order", today.plusDays(2), today.plusDays(3));
        cancelled.changeStatus(ReservationStatus.CANCELLED);
        repository.saveAndFlush(cancelled);

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchFor("drill-001", today.plusDays(1), today.plusDays(2))))
                .andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
        verify(inventoryGateway).claim(any(), org.mockito.ArgumentMatchers.eq(List.of("drill-001")));
    }

    @Test
    void reportsAllLocalConflictsInRequestOrder() throws Exception {
        LocalDate today = creationRequestRepository.databaseUtcDate();
        repository.saveAllAndFlush(List.of(
                new Reservation("SAW-001", "old-customer", "old-order", today, today.plusDays(2)),
                new Reservation("DRILL-001", "old-customer", "old-order", today, today.plusDays(2))));
        String body = """
                {"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[
                  {"serialNumber":"SAW-001","startDate":"%s","endDate":"%s"},
                  {"serialNumber":"MIXER-001","startDate":"%s","endDate":"%s"},
                  {"serialNumber":"DRILL-001","startDate":"%s","endDate":"%s"}
                ]}
                """.formatted(
                        today.plusDays(1),
                        today.plusDays(2),
                        today.plusDays(1),
                        today.plusDays(2),
                        today.plusDays(1),
                        today.plusDays(2));

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failedItems", hasSize(2)))
                .andExpect(jsonPath("$.failedItems[0].index").value(0))
                .andExpect(jsonPath("$.failedItems[0].serialNumber").value("SAW-001"))
                .andExpect(jsonPath("$.failedItems[1].index").value(2))
                .andExpect(jsonPath("$.failedItems[1].serialNumber").value("DRILL-001"));

        verify(inventoryGateway, times(0)).claim(any(), any());
    }

    @Test
    void replaysACompletedBatchWithoutCallingInventoryAgain() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        MvcResult firstResult = mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        byte[] first = firstResult.getResponse().getContentAsByteArray();
        String firstExpiry = firstResult.getResponse().getHeader("Idempotency-Key-Expires-At");
        MvcResult replayResult = mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Idempotency-Key-Expires-At", firstExpiry))
                .andReturn();
        byte[] replay = replayResult.getResponse().getContentAsByteArray();

        assertThat(replay).isEqualTo(first);
        assertThat(repository.count()).isOne();
        verify(inventoryGateway, times(1)).claim(any(), any());
    }

    @Test
    void rollsBackTheWholeBatchWhenInventoryRejectsOneItem() throws Exception {
        when(inventoryGateway.claim(any(), any()))
                .thenReturn(ClaimResult.terminal(
                        ClaimResult.Type.UNAVAILABLE,
                        List.of(new ReservationCreationFailure(
                                1, "MIXER-001", "INVENTORY_ITEM_UNAVAILABLE", "Inventory item is not available."))));
        String body = """
                {"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[
                  {"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"},
                  {"serialNumber":"MIXER-001","startDate":"2026-10-01","endDate":"2026-10-03"}
                ]}
                """;

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.failedItems[0].index").value(1));
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "MISSING,422,INVENTORY_ITEM_NOT_FOUND",
        "INVALID_REFERENCE,400,INVALID_INVENTORY_REFERENCE",
        "KEY_REUSED,422,IDEMPOTENCY_KEY_REUSED"
    })
    void storesAndReplaysOtherTerminalInventoryFailures(String type, int statusCode, String code) throws Exception {
        UUID key = UUID.randomUUID();
        ClaimResult.Type resultType = ClaimResult.Type.valueOf(type);
        List<ReservationCreationFailure> failures = resultType == ClaimResult.Type.KEY_REUSED
                ? List.of()
                : List.of(new ReservationCreationFailure(
                        0,
                        "DRILL-001",
                        resultType == ClaimResult.Type.MISSING
                                ? "INVENTORY_ITEM_NOT_FOUND"
                                : "INVALID_INVENTORY_REFERENCE",
                        "Safe failure."));
        when(inventoryGateway.claim(any(), any())).thenReturn(ClaimResult.terminal(resultType, failures));

        for (int request = 0; request < 2; request++) {
            mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batch(CREATE)))
                    .andExpect(status().is(statusCode))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(header().string("Idempotency-Replayed", request == 0 ? "false" : "true"));
        }

        assertThat(repository.count()).isZero();
        assertThat(creationRequestRepository.count()).isOne();
        verify(inventoryGateway).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void resumesTemporaryInventoryFailureWithTheSameKey() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        when(inventoryGateway.claim(any(), any()))
                .thenThrow(new InventoryServiceUnavailableException(new IllegalStateException()))
                .thenReturn(ClaimResult.claimed());

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));
        assertThat(repository.count()).isZero();
        assertThat(creationRequestRepository.count()).isZero();

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        assertThat(repository.count()).isOne();
        verify(inventoryGateway, times(2)).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void treatsAnExpiredKeyAsANewIntent() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        Reservation existing = repository.findAll().getFirst();
        existing.changeStatus(ReservationStatus.CANCELLED);
        repository.saveAndFlush(existing);
        jdbc.update("""
                UPDATE reservation.reservation_creation_requests
                SET recorded_at = statement_timestamp() - INTERVAL '169 hours',
                    expires_at = statement_timestamp() - INTERVAL '1 hour'
                WHERE idempotency_key = ?
                """, key);

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("DRILL-001", "SAW-001")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(creationRequestRepository.count()).isOne();
        verify(inventoryGateway, times(2)).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void retriesProtocolAmbiguityOnlyWhenTheClientRetries() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        when(inventoryGateway.claim(any(), any()))
                .thenThrow(new InventoryProtocolException(new IllegalStateException()));

        for (int request = 0; request < 2; request++) {
            mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_ERROR"));
        }

        assertThat(repository.count()).isZero();
        assertThat(creationRequestRepository.count()).isZero();
        verify(inventoryGateway, times(2)).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void replaysAStoredInventoryRejectionAfterConditionsChange() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        when(inventoryGateway.claim(any(), any()))
                .thenReturn(ClaimResult.terminal(
                        ClaimResult.Type.UNAVAILABLE,
                        List.of(new ReservationCreationFailure(
                                0, "DRILL-001", "INVENTORY_ITEM_UNAVAILABLE", "Inventory item is not available."))));

        for (int request = 0; request < 2; request++) {
            mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVENTORY_ITEM_UNAVAILABLE"))
                    .andExpect(header().string("Idempotency-Replayed", request == 0 ? "false" : "true"));
        }

        assertThat(repository.count()).isZero();
        assertThat(creationRequestRepository.count()).isOne();
        verify(inventoryGateway, times(1)).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void returnsFixedRetryHeaderWhenInventoryIsBusy() throws Exception {
        when(inventoryGateway.claim(any(), any())).thenReturn(ClaimResult.busy());

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(CREATE)))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));

        assertThat(repository.count()).isZero();
        assertThat(creationRequestRepository.count()).isZero();
    }

    @Test
    void returnsBusyImmediatelyForAConcurrentRequestWithTheSameKey() throws Exception {
        UUID key = UUID.randomUUID();
        String body = batch(CREATE);
        CountDownLatch inventoryEntered = new CountDownLatch(1);
        CountDownLatch allowInventoryResponse = new CountDownLatch(1);
        when(inventoryGateway.claim(any(), any())).thenAnswer(invocation -> {
            inventoryEntered.countDown();
            assertThat(allowInventoryResponse.await(5, TimeUnit.SECONDS)).isTrue();
            return ClaimResult.claimed();
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> first = executor.submit(() -> mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse()
                    .getStatus());
            assertThat(inventoryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));

            allowInventoryResponse.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        }

        assertThat(repository.count()).isOne();
        assertThat(creationRequestRepository.count()).isOne();
        verify(inventoryGateway).claim(org.mockito.ArgumentMatchers.eq(key), any());
    }

    @Test
    void hasNoAutomaticReservationCreationRecoveryOrWorkflowBean() {
        assertThat(applicationContext.getBeanDefinitionNames()).noneMatch(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("reservationcreationrecovery") || lower.contains("reservationcreationstore");
        });
    }

    @Test
    void configuresFiveExponentialJitteredForegroundAttempts() {
        RetryConfig retryConfig = retryRegistry.retry("inventory").getRetryConfig();
        assertThat(retryConfig.getMaxAttempts()).isEqualTo(5);
        long[] minimums = {160, 320, 640, 1280};
        long[] maximums = {240, 480, 960, 1920};
        for (int attempt = 1; attempt <= 4; attempt++) {
            long wait = retryConfig.getIntervalBiFunction().apply(attempt, Either.left(new IllegalStateException()));
            assertThat(wait).isBetween(minimums[attempt - 1], maximums[attempt - 1]);
        }
    }

    @Test
    void cleanupDeletesOnlyExpiredLedgerRows() throws Exception {
        UUID expiredKey = UUID.randomUUID();
        UUID retainedKey = UUID.randomUUID();
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", expiredKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(CREATE)))
                .andExpect(status().isCreated());
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", retainedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(CREATE.replace("DRILL-001", "SAW-001"))))
                .andExpect(status().isCreated());
        jdbc.update("""
                UPDATE reservation.reservation_creation_requests
                SET recorded_at = statement_timestamp() - INTERVAL '169 hours',
                    expires_at = statement_timestamp() - INTERVAL '1 hour'
                WHERE idempotency_key = ?
                """, expiredKey);

        assertThat(creationCleanup.deleteChunk()).isOne();

        assertThat(creationRequestRepository.findById(expiredKey)).isEmpty();
        assertThat(creationRequestRepository.findById(retainedKey)).isPresent();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void rejectsAChangedPayloadForAnUnexpiredKey() throws Exception {
        UUID key = UUID.randomUUID();
        String first = batch(CREATE);
        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated());

        mvc.perform(post(PATH)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first.replace("ORDER-001", "ORDER-002")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(repository.count()).isOne();
        verify(inventoryGateway, times(1)).claim(any(), any());
    }

    @Test
    void rejectsMissingKeyAndReplaysStoredDuplicateSerialValidation() throws Exception {
        String duplicate = """
                {"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[
                  {"serialNumber":"DRILL-001","startDate":"2026-10-01","endDate":"2026-10-03"},
                  {"serialNumber":"DRILL-001","startDate":"2026-10-04","endDate":"2026-10-05"}
                ]}
                """;
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(batch(CREATE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));
        UUID key = UUID.randomUUID();
        for (int request = 0; request < 2; request++) {
            mvc.perform(post(PATH)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(duplicate))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].field").value("items[1].serialNumber"))
                    .andExpect(header().string("Idempotency-Replayed", request == 0 ? "false" : "true"));
        }

        assertThat(creationRequestRepository.count()).isOne();
        assertThat(repository.count()).isZero();
        verify(inventoryGateway, times(0)).claim(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONFIRMED", "CANCELLED"})
    void replacementUpdatesAllMutableFieldsAndPreservesManagedFields(String newStatus) throws Exception {
        JsonNode original = create(CREATE);
        String replacement = """
            {"serialNumber":"SAW-002","customerId":"CUSTOMER-002","orderId":"ORDER-002","startDate":"2027-01-01","endDate":"2027-01-02","status":"%s"}
            """.formatted(newStatus);
        JsonNode updated = body(mvc.perform(put(PATH + "/" + original.path("id").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("SAW-002"))
                .andExpect(jsonPath("$.customerId").value("CUSTOMER-002"))
                .andExpect(jsonPath("$.orderId").value("ORDER-002"))
                .andExpect(jsonPath("$.startDate").value("2027-01-01"))
                .andExpect(jsonPath("$.endDate").value("2027-01-02"))
                .andExpect(jsonPath("$.status").value(newStatus)));
        assertThat(updated.path("id")).isEqualTo(original.path("id"));
        assertThat(updated.path("timestamp")).isEqualTo(original.path("timestamp"));
        assertThat(read(updated.path("id").asString())).isEqualTo(updated);
    }

    @Test
    void statusTransitionsAreUnrestrictedInThisScaffold() throws Exception {
        String id = create(CREATE).path("id").asString();
        for (String value : List.of("CANCELLED", "CONFIRMED", "HELD")) {
            mvc.perform(put(PATH + "/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withStatus(CREATE, value)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(value));
        }
    }

    @Test
    void permanentlyDeletesAndReturnsNotFoundThereafter() throws Exception {
        String id = create(CREATE).path("id").asString();
        mvc.perform(delete(PATH + "/" + id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        problem(get(PATH + "/" + id), 404, "RESERVATION_NOT_FOUND");
        problem(delete(PATH + "/" + id), 404, "RESERVATION_NOT_FOUND");
        assertThat(repository.count()).isZero();
    }

    @Test
    void missingGetReplaceAndDeleteNeverCreate() throws Exception {
        String path = PATH + "/" + UUID.randomUUID();
        problem(get(path), 404, "RESERVATION_NOT_FOUND");
        problem(
                put(path).contentType(MediaType.APPLICATION_JSON).content(withStatus(CREATE, "HELD")),
                404,
                "RESERVATION_NOT_FOUND");
        problem(delete(path), 404, "RESERVATION_NOT_FOUND");
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void rejectsInvalidCreationWithoutWriting(String request) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(repository.count()).isZero();
    }

    static Stream<String> invalidCreateBodies() {
        return Stream.of(
                "",
                "{",
                "{}",
                "null",
                "[]",
                CREATE.replace("DRILL-001", ""),
                CREATE.replace("DRILL-001", "/bad"),
                CREATE.replace("DRILL-001", "X".repeat(65)),
                CREATE.replace("CUSTOMER-001", " "),
                CREATE.replace("CUSTOMER-001", "X".repeat(65)),
                CREATE.replace("ORDER-001", ""),
                CREATE.replace("ORDER-001", "X".repeat(65)),
                CREATE.replace("\"CUSTOMER-001\"", "null"),
                CREATE.replace("\"ORDER-001\"", "{}"),
                CREATE.replace("\"2026-10-01\"", "null"),
                CREATE.replace("\"2026-10-01\"", "123"),
                CREATE.replace("2026-10-01", "2026-10-04"),
                CREATE.replace("2026-10-01", "2026-02-30"),
                CREATE.replace("2026-10-01", "0000-01-01"),
                CREATE.replace("2026-10-03", "+10000-01-01"),
                CREATE.replace("2026-10-01", "2026-10-01T12:00:00Z"),
                withStatus(CREATE, "HELD"),
                CREATE.replace("}", ",\"id\":\"ef469102-af79-4a47-9afb-f34937c9481f\"}"),
                CREATE.replace("}", ",\"timestamp\":\"2026-01-01T00:00:00Z\"}"),
                CREATE.replace("}", ",\"holdExpiresAt\":\"2026-01-01T00:10:00Z\"}"),
                CREATE.replace("}", ",\"unknown\":true}"));
    }

    @Test
    void rejectedReplacementsPreserveTheEntireOriginal() throws Exception {
        JsonNode original = create(CREATE);
        String id = original.path("id").asString();
        for (String invalid : List.of(
                CREATE,
                "{",
                withStatus(CREATE, "EXPIRED"),
                withStatus(CREATE, "held"),
                withStatus(CREATE, "0"),
                withStatus(CREATE, "HELD").replace("2026-10-03", "2026-09-30"),
                withStatus(CREATE, "HELD").replace("}", ",\"id\":\"" + id + "\"}"))) {
            mvc.perform(put(PATH + "/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest());
            assertThat(read(id)).isEqualTo(original);
        }
        assertThat(repository.count()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01", "9999-12-31"})
    void acceptsSameDayCurrentOrFuturePeriods(String day) throws Exception {
        create(CREATE.replace("2026-10-01", day).replace("2026-10-03", day));
    }

    @ParameterizedTest
    @MethodSource("invalidReplacementBodies")
    void sharedDtoRejectsInvalidReplacementWithoutChangingPersistedData(String request) throws Exception {
        JsonNode original = create(CREATE);
        String id = original.path("id").asString();
        mvc.perform(put(PATH + "/" + id).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(read(id)).isEqualTo(original);
        assertThat(repository.count()).isOne();
    }

    static Stream<String> invalidReplacementBodies() {
        String valid = withStatus(CREATE, "CONFIRMED");
        return Stream.of(
                "{}",
                "null",
                "[]",
                "",
                valid.replace("\"serialNumber\":\"DRILL-001\",", ""),
                valid.replace("\"customerId\":\"CUSTOMER-001\",", ""),
                valid.replace("\"orderId\":\"ORDER-001\",", ""),
                valid.replace("\"startDate\":\"2026-10-01\",", ""),
                valid.replace("\"endDate\":\"2026-10-03\",", ""),
                CREATE,
                valid.replace("DRILL-001", ""),
                valid.replace("DRILL-001", "/bad"),
                valid.replace("DRILL-001", "X".repeat(65)),
                valid.replace("CUSTOMER-001", " "),
                valid.replace("CUSTOMER-001", "X".repeat(65)),
                valid.replace("ORDER-001", " "),
                valid.replace("ORDER-001", "X".repeat(65)),
                valid.replace("\"CUSTOMER-001\"", "{}"),
                valid.replace("\"ORDER-001\"", "[]"),
                valid.replace("\"CONFIRMED\"", "null"),
                valid.replace("\"CONFIRMED\"", "1"),
                valid.replace("\"CONFIRMED\"", "true"),
                valid.replace("2026-10-01", "2026-02-30"),
                valid.replace("2026-10-03", "2026-09-30"),
                valid.replace("}", ",\"unknown\":true}"),
                valid.replace("}", ",\"id\":null}"),
                valid.replace("}", ",\"id\":\"ef469102-af79-4a47-9afb-f34937c9481f\"}"),
                valid.replace("}", ",\"timestamp\":null}"),
                valid.replace("}", ",\"timestamp\":\"2026-01-01T00:00:00Z\"}"),
                valid.replace("}", ",\"holdExpiresAt\":null}"),
                valid.replace("}", ",\"holdExpiresAt\":\"2026-01-01T00:10:00Z\"}"));
    }

    @Test
    void missingFieldsProduceSortedViolations() throws Exception {
        problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.violations", hasSize(3)))
                .andExpect(jsonPath("$.violations[0].field").value("customerId"))
                .andExpect(jsonPath("$.violations[1].field").value("items"))
                .andExpect(jsonPath("$.violations[2].field").value("orderId"));
    }

    @Test
    void returnsAStableBoundedPageEnvelopeAndAccurateEmptyPages() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create(CREATE.replace("DRILL-001", "DRILL-00" + (i + 1)))
                    .path("id")
                    .asString());
        }
        ids.sort(Comparator.naturalOrder());
        JsonNode page = body(mvc.perform(get(PATH)).andExpect(status().isOk()));
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("content", "page");
        assertThat(page.path("page").propertyNames())
                .containsExactlyInAnyOrder("size", "number", "totalElements", "totalPages");
        assertThat(page.path("content")
                        .valueStream()
                        .map(node -> node.path("id").asString())
                        .toList())
                .containsExactlyElementsOf(ids);
        mvc.perform(get(PATH).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mvc.perform(get(PATH).param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.number").value(5));
        mvc.perform(get(PATH).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @ParameterizedTest
    @CsvSource({
        "id,asc",
        "id,desc",
        "serialNumber,asc",
        "serialNumber,desc",
        "customerId,asc",
        "customerId,desc",
        "orderId,asc",
        "orderId,desc",
        "startDate,asc",
        "startDate,desc",
        "endDate,asc",
        "endDate,desc",
        "timestamp,asc",
        "timestamp,desc",
        "status,asc",
        "status,desc"
    })
    void sortsAllFieldsDeterministically(String field, String direction) throws Exception {
        List<JsonNode> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String suffix = "%03d".formatted(i + 1);
            String input = CREATE.replace("001", suffix).replace("2026-10-01", "2026-10-0" + (i + 1));
            JsonNode created = create(input);
            String replacement = withStatus(input, i == 0 ? "CANCELLED" : "CONFIRMED");
            expected.add(body(mvc.perform(put(PATH + "/" + created.path("id").asString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replacement))
                    .andExpect(status().isOk())));
        }
        Comparator<JsonNode> comparator =
                Comparator.comparing(node -> node.path(field).asString());
        if (direction.equals("desc")) {
            comparator = comparator.reversed();
        }
        if (!field.equals("id")) {
            comparator = comparator.thenComparing(node -> node.path("id").asString());
        }
        expected.sort(comparator);
        JsonNode page = body(mvc.perform(
                        get(PATH).param("sort", field).param("direction", direction.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk()));
        assertThat(page.path("content").valueStream().toList()).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "page,-1",
        "page,nope",
        "page,2147483648",
        "page,2147483647",
        "size,0",
        "size,101",
        "size,2.5",
        "sort,unknown",
        "direction,sideways",
        "filter,x"
    })
    void rejectsInvalidCollectionParameters(String parameter, String value) throws Exception {
        problem(get(PATH).param(parameter, value), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "sort", "direction"})
    void rejectsBlankAndRepeatedParameters(String parameter) throws Exception {
        problem(get(PATH).param(parameter, ""), 400, "VALIDATION_FAILED");
        String value =
                switch (parameter) {
                    case "page" -> "0";
                    case "size" -> "20";
                    case "sort" -> "id";
                    default -> "asc";
                };
        problem(get(PATH).param(parameter, value, value), 400, "VALIDATION_FAILED");
    }

    @Test
    void frameworkErrorsUseProblemDetailsAndPreserveProtocolHeaders() throws Exception {
        problem(get(PATH + "/not-a-uuid"), 400, "VALIDATION_FAILED");
        problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{"), 400, "MALFORMED_JSON");
        problem(
                        patch(PATH + "/" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        405,
                        "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", containsString("PUT")));
        problem(post(PATH).contentType(MediaType.TEXT_PLAIN).content(CREATE), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(get(PATH).accept(MediaType.APPLICATION_XML), 406, "NOT_ACCEPTABLE");
        problem(get("/api/v1/missing"), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void healthProbesExposeOnlyStatusAndOtherActuatorEndpointsStayHidden() throws Exception {
        for (String path : List.of("/livez", "/readyz", "/actuator/health")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    private JsonNode create(String request) throws Exception {
        ResultActions result = mvc.perform(post(PATH)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().doesNotExist("Location"));
        JsonNode created = body(result);
        assertThat(created.isArray()).isTrue();
        return created.get(0);
    }

    private String batch(String request) throws Exception {
        JsonNode item = mapper.readTree(request);
        return """
                {"customerId":"%s","orderId":"%s","items":[{"serialNumber":"%s","startDate":"%s","endDate":"%s"}]}
                """.formatted(
                        item.path("customerId").asString(),
                        item.path("orderId").asString(),
                        item.path("serialNumber").asString(),
                        item.path("startDate").asString(),
                        item.path("endDate").asString());
    }

    private String batchFor(String serialNumber, LocalDate startDate, LocalDate endDate) {
        return """
                {"customerId":"CUSTOMER-001","orderId":"ORDER-001","items":[
                  {"serialNumber":"%s","startDate":"%s","endDate":"%s"}
                ]}
                """.formatted(serialNumber, startDate, endDate);
    }

    private JsonNode read(String id) throws Exception {
        return body(mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static String withStatus(String request, String value) {
        return request.replace("}", ",\"status\":\"" + value + "\"}");
    }

    private ResultActions problem(MockHttpServletRequestBuilder request, int statusCode, String code) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type")
                        .value("urn:rentflow:problem:"
                                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").isString());
    }
}
