package com.rentflow.config;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rentflow.dto.ProblemResponse;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

public final class ReservationSecurityResponses {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationSecurityResponses.class);
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public ReservationSecurityResponses(ObjectMapper mapper, MeterRegistry metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public void write(
            HttpServletRequest request, HttpServletResponse response, int status, String surface, String challenge)
            throws IOException {
        String reason = status == 401 ? "authentication" : "authorization";
        metrics.counter("reservation.security.denials", "reason", reason, "surface", surface)
                .increment();
        LOGGER.atInfo()
                .addKeyValue("reason", reason)
                .addKeyValue("surface", surface)
                .addKeyValue("status", status)
                .log("Reservation security denial");
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (challenge != null) {
            response.setHeader("WWW-Authenticate", challenge);
        }
        ProblemResponse problem = status == 401
                ? new ProblemResponse(
                        "urn:rentflow:problem:authentication-required",
                        "Authentication required",
                        401,
                        "Valid bearer authentication is required.",
                        request.getRequestURI(),
                        "AUTHENTICATION_REQUIRED",
                        List.of())
                : new ProblemResponse(
                        "urn:rentflow:problem:access-denied",
                        "Access denied",
                        403,
                        "This operation is not permitted.",
                        request.getRequestURI(),
                        "ACCESS_DENIED",
                        List.of());
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
