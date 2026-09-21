package com.rentflow;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.model.Reservation;
import com.rentflow.model.ReservationCancellationOutbox;
import com.rentflow.model.ReservationStatus;
import com.rentflow.repository.ReservationCancellationOutboxRepository;
import com.rentflow.repository.ReservationRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationCancellationRollbackIT extends PostgresIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ReservationRepository reservations;

    @MockitoSpyBean
    private ReservationCancellationOutboxRepository outboxes;

    @BeforeEach
    void clearDatabase() {
        reset(outboxes);
        outboxes.deleteAllInBatch();
        reservations.deleteAllInBatch();
    }

    @Test
    void outboxWriteFailureRollsBackTheStatusAndReturnsSafeProblemDetails() throws Exception {
        Reservation reservation = reservations.saveAndFlush(new Reservation(
                "ROLLBACK-001", "CUSTOMER-001", "ORDER-001", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        doThrow(new IllegalStateException("sensitive persistence detail"))
                .when(outboxes)
                .save(any(ReservationCancellationOutbox.class));

        mvc.perform(post("/api/v1/reservations/" + reservation.getId() + "/cancel"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

        reset(outboxes);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(outboxes.count()).isZero();
    }
}
