package com.rentflow.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import com.rentflow.dto.ProblemResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void unexpectedFailureReturnsAFixedSanitizedProblem() {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/reservations"));
        ResponseEntity<ProblemResponse> response = new ApiExceptionHandler()
                .handleUnexpected(new IllegalStateException("internal database diagnostics"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:internal-error",
                        "Internal server error",
                        500,
                        "An unexpected error occurred.",
                        "/api/v1/reservations",
                        "INTERNAL_ERROR",
                        java.util.List.of()));
    }
}
