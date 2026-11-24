package com.rentflow;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentflow.model.ReservationCreationRequest;
import com.rentflow.model.ReservationCreationRequestId;
import com.rentflow.repository.ReservationCreationRequestRepository;
import com.rentflow.service.ReservationCleanupService;
import com.rentflow.service.ReservationCreationOutcomes;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityPersistenceIT extends PostgresIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.rentflow.repository.ReservationRepository reservations;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired
    ReservationCreationRequestRepository requests;

    @Autowired
    ReservationCleanupService cleanup;

    @BeforeEach
    void prepare() {
        requests.deleteAllInBatch();
        reservations.deleteAllInBatch();
    }

    @Test
    void scopesQueriesByExactIssuerAndSubjectWithoutMappings() {
        java.time.LocalDate date = java.time.LocalDate.now();
        com.rentflow.model.Reservation own = reservations.saveAndFlush(
                new com.rentflow.model.Reservation("OWN", "same", "https://one.example", "ORDER", date, date));
        reservations.saveAndFlush(
                new com.rentflow.model.Reservation("OTHER", "same", "https://two.example", "ORDER", date, date));
        reservations.saveAndFlush(
                new com.rentflow.model.Reservation("CASE", "Same", "https://one.example", "ORDER", date, date));
        assertThat(reservations.findByIdAndCustomerIssuerAndCustomerId(own.getId(), "https://one.example", "same"))
                .isPresent();
        assertThat(reservations.findByIdAndCustomerIssuerAndCustomerId(own.getId(), "https://two.example", "same"))
                .isEmpty();
        assertThat(reservations
                        .findAllByCustomerIssuerAndCustomerId(
                                "https://one.example", "same", org.springframework.data.domain.PageRequest.of(0, 1))
                        .getTotalElements())
                .isOne();
        assertThat(reservations.countByIdInAndCustomerIssuerAndCustomerId(
                        java.util.List.of(own.getId()), "https://two.example", "same"))
                .isZero();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(_ -> {
            assertThat(reservations.findForUpdateByIdAndCustomerIssuerAndCustomerId(
                            own.getId(), "https://two.example", "same"))
                    .isEmpty();
            assertThat(reservations.findForUpdateByIdAndCustomerIssuerAndCustomerId(
                            own.getId(), "https://one.example", "same"))
                    .isPresent();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT to_regclass('reservation.customer_identity_mappings') IS NULL", Boolean.class))
                .isTrue();
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE reservation.reservations SET customer_issuer=' ' WHERE id=?", own.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE reservation.reservations SET customer_id=' ' WHERE id=?", own.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cleanupDeletesOnlyTheExpiredCompositeRowAndCountsEqualExternalKeysIndependently() {
        UUID key = UUID.randomUUID();
        ReservationCreationRequestId expired = new ReservationCreationRequestId("p1:" + "a".repeat(64), key);
        ReservationCreationRequestId retained = new ReservationCreationRequestId("p1:" + "b".repeat(64), key);
        Instant now = requests.databaseTime();
        save(expired, now.minus(java.time.Duration.ofDays(8)), "A");
        save(retained, now, "B");
        assertThat(requests.count()).isEqualTo(2);
        assertThat(requests.countExpired()).isOne();
        assertThat(requests.monitoringSnapshot().getBacklog()).isOne();
        assertThat(cleanup.deleteExpiredCreationChunk()).isOne();
        assertThat(requests.findById(expired)).isEmpty();
        assertThat(requests.findById(retained))
                .get()
                .extracting(ReservationCreationRequest::getTargetCustomerId)
                .isEqualTo("B");
        assertThat(new ReservationCreationRequestId(retained.callerScope(), key))
                .isEqualTo(retained);
    }

    private void save(ReservationCreationRequestId id, Instant time, String customer) {
        ReservationCreationRequest request = new ReservationCreationRequest(id);
        request.complete("0".repeat(64), ReservationCreationOutcomes.keyReused(), time, customer);
        requests.saveAndFlush(request);
    }
}
