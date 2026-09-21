package com.rentflow.controller;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rentflow.converter.ReservationConverter;
import com.rentflow.dto.CreateReservationsRequest;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ReservationCreationProblemResponse;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.service.IdempotencyKeyParser;
import com.rentflow.service.ReservationCancellationService;
import com.rentflow.service.ReservationCreationService;
import com.rentflow.service.ReservationService;
import com.rentflow.service.ReservationSortField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Reservations")
@RequestMapping(path = ReservationController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request, JSON, identifier, or period.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemResponse.class))),
    @ApiResponse(
            responseCode = "405",
            description = "Method unsupported (including PATCH); Allow lists supported methods.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemResponse.class))),
    @ApiResponse(
            responseCode = "406",
            description = "No acceptable response representation.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemResponse.class))),
    @ApiResponse(
            responseCode = "500",
            description = "Unexpected server failure.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemResponse.class)))
})
public class ReservationController {
    public static final String PATH = "/api/v1/reservations";
    private static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    private static final Set<String> COLLECTION_PARAMETERS = Set.of("page", "size", "sort", "direction");
    private final ReservationService service;
    private final ReservationCreationService creationService;
    private final ReservationCancellationService cancellationService;
    private final ReservationConverter converter;

    public ReservationController(
            ReservationService service,
            ReservationCreationService creationService,
            ReservationCancellationService cancellationService,
            ReservationConverter converter) {
        this.service = service;
        this.creationService = creationService;
        this.cancellationService = cancellationService;
        this.converter = converter;
    }

    @Operation(
            operationId = "createReservation",
            summary = "Create an atomic batch of HELD reservations",
            description =
                    "Each item must start on or after the PostgreSQL UTC date. Terminal outcomes replay for seven days from a compact database ledger; creation runs only within the incoming request.",
            parameters =
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Endpoint-global canonical UUID v4 used for retry and replay.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            pattern = IdempotencyKeyParser.UUID_V4_PATTERN)))
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Every requested Reservation was created with HELD status.",
                headers = {
                    @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean")),
                    @Header(name = "Idempotency-Key-Expires-At", schema = @Schema(format = "date-time"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                array =
                                        @io.swagger.v3.oas.annotations.media.ArraySchema(
                                                schema = @Schema(implementation = ReservationDTO.class)))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid input. Command-level and Inventory-reference failures are stored; framework binding failures are not.",
                headers = {
                    @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean")),
                    @Header(name = "Idempotency-Key-Expires-At", schema = @Schema(format = "date-time"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "An active reservation, unavailable Inventory item, or busy key prevented creation.",
                headers = {
                    @Header(
                            name = "Retry-After",
                            description = "One second for IDEMPOTENCY_IN_PROGRESS only.",
                            schema = @Schema(type = "integer")),
                    @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean")),
                    @Header(name = "Idempotency-Key-Expires-At", schema = @Schema(format = "date-time"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ReservationCreationProblemResponse.class))),
        @ApiResponse(
                responseCode = "422",
                description = "An Inventory item is missing or an idempotency key was reused.",
                headers = {
                    @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean")),
                    @Header(name = "Idempotency-Key-Expires-At", schema = @Schema(format = "date-time"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ReservationCreationProblemResponse.class))),
        @ApiResponse(
                responseCode = "502",
                description = "Inventory returned an unexpected response; the outcome is not stored."),
        @ApiResponse(
                responseCode = "503",
                description = "Inventory remained unavailable after foreground retries; the outcome is not stored."),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported request media type.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            HttpServletRequest servletRequest, @Valid @RequestBody CreateReservationsRequest request) {
        UUID idempotencyKey;
        try {
            idempotencyKey = IdempotencyKeyParser.parse(Collections.list(servletRequest.getHeaders("Idempotency-Key")));
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("Idempotency-Key", "must contain exactly one canonical UUID v4 value");
        }
        ReservationCreationService.Result result = creationService.create(idempotencyKey, converter.toCommand(request));
        return creationResponse(result);
    }

    private ResponseEntity<?> creationResponse(ReservationCreationService.Result result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
        if (result.expiresAt() != null) {
            response.header("Idempotency-Replayed", Boolean.toString(result.replayed()));
            response.header("Idempotency-Key-Expires-At", result.expiresAt().toString());
        }
        if (IDEMPOTENCY_IN_PROGRESS.equals(result.code())) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        MediaType contentType =
                result.status() == 201 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON;
        return response.contentType(contentType).body(result.body());
    }

    @Operation(operationId = "getReservation", summary = "Retrieve a reservation")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation found."),
        @ApiResponse(
                responseCode = "404",
                description = "Reservation not found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/{id}")
    public ReservationDTO get(@PathVariable UUID id) {
        return converter.toResponse(service.get(id));
    }

    @Operation(
            operationId = "cancelReservation",
            summary = "Cancel a reservation",
            description =
                    "Changes an eligible reservation to CANCELLED and durably records an event for asynchronous Inventory release. A 204 response reports only the committed Reservation outcome; Kafka publication and Inventory release may complete later.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "204",
                description = "Reservation cancelled or already cancelled.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "Reservation not found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        cancellationService.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "replaceReservation",
            summary = "Fully replace reservation details and status",
            description =
                    "Preserves ID and creation timestamp; requires every mutable field. Any status is accepted without transition rules. Does not create missing reservations.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation replaced."),
        @ApiResponse(
                responseCode = "404",
                description = "Reservation not found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported request media type.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReservationDTO replace(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "All six mutable fields; do not send ID or timestamp.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = ReservationDTO.class),
                                            examples = @ExampleObject(name = "replacement", value = """
                                    {
                                      "serialNumber": "DRILL-001",
                                      "customerId": "CUSTOMER-001",
                                      "orderId": "ORDER-001",
                                      "startDate": "2026-10-01",
                                      "endDate": "2026-10-03",
                                      "status": "CONFIRMED"
                                    }
                                    """)))
                    @Valid @RequestBody
                    ReservationDTO request) {
        validatePeriod(request.startDate(), request.endDate());
        return converter.toResponse(service.replace(id, converter.toModel(request)));
    }

    @Operation(operationId = "deleteReservation", summary = "Permanently delete a reservation")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Reservation deleted.", content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "Reservation not found.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "listReservations",
            summary = "Browse a bounded reservation page",
            description =
                    "Ties use id ASC. Unknown, repeated, blank, or invalid parameters are rejected; filtering is unsupported. The page * size offset must not exceed 2147483647.")
    @ApiResponse(responseCode = "200", description = "Reservation page, including accurate totals for empty pages.")
    @GetMapping
    public PagedModel<ReservationDTO> list(
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(description = "Zero-based page number.", schema = @Schema(defaultValue = "0", minimum = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(value = 0, message = "must be at least 0") int page,
            @Parameter(
                            description = "Maximum page size.",
                            schema = @Schema(defaultValue = "20", minimum = "1", maximum = "100"))
                    @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "must be at least 1") @Max(value = 100, message = "must be at most 100") int size,
            @Parameter(
                            description = "Primary sort field.",
                            schema =
                                    @Schema(
                                            defaultValue = "id",
                                            allowableValues = {
                                                "id",
                                                "serialNumber",
                                                "customerId",
                                                "orderId",
                                                "startDate",
                                                "endDate",
                                                "timestamp",
                                                "status"
                                            }))
                    @RequestParam(defaultValue = "id")
                    String sort,
            @Parameter(
                            description = "Primary direction (case-insensitive).",
                            schema =
                                    @Schema(
                                            defaultValue = "asc",
                                            allowableValues = {"asc", "desc"}))
                    @RequestParam(defaultValue = "asc")
                    String direction) {
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (!COLLECTION_PARAMETERS.contains(parameter.getKey())) {
                throw new RequestValidationException(parameter.getKey(), "is not supported");
            }
            if (parameter.getValue().length != 1 || parameter.getValue()[0].isBlank()) {
                throw new RequestValidationException(parameter.getKey(), "must have exactly one nonblank value");
            }
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new RequestValidationException("page", "page offset must be at most 2147483647");
        }
        ReservationSortField field;
        try {
            field = ReservationSortField.fromApiName(sort);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("sort", "must be a supported reservation field");
        }
        Sort.Direction sortDirection;
        try {
            sortDirection = Sort.Direction.fromString(direction);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException("direction", "must be asc or desc");
        }
        return new PagedModel<>(service.list(page, size, field, sortDirection).map(converter::toResponse));
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate.getYear() < 1 || startDate.getYear() > 9999) {
            throw new RequestValidationException("startDate", "year must be between 0001 and 9999");
        }
        if (endDate.getYear() < 1 || endDate.getYear() > 9999) {
            throw new RequestValidationException("endDate", "year must be between 0001 and 9999");
        }
        if (endDate.isBefore(startDate)) {
            throw new RequestValidationException("endDate", "must be on or after startDate");
        }
    }
}
