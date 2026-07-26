package com.rentflow.controller;

import java.net.URI;
import java.time.LocalDate;
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
import com.rentflow.dto.CreateReservationRequest;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.service.ReservationService;
import com.rentflow.service.ReservationSortField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    private static final Set<String> COLLECTION_PARAMETERS = Set.of("page", "size", "sort", "direction");
    private final ReservationService service;
    private final ReservationConverter converter;

    public ReservationController(ReservationService service, ReservationConverter converter) {
        this.service = service;
        this.converter = converter;
    }

    @Operation(
            operationId = "createReservation",
            summary = "Create a HELD reservation",
            description =
                    "No uniqueness, availability, or external existence checks. The inclusive period may be historical or a single day.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Reservation created with HELD status.",
                headers =
                        @Header(
                                name = HttpHeaders.LOCATION,
                                description = "Canonical relative reservation path.",
                                schema = @Schema(type = "string", format = "uri-reference")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = ReservationDTO.class))),
        @ApiResponse(
                responseCode = "415",
                description = "Unsupported request media type.",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationDTO> create(@Valid @RequestBody CreateReservationRequest request) {
        validatePeriod(request.startDate(), request.endDate());
        ReservationDTO response = converter.toResponse(service.create(converter.toModel(request)));
        return ResponseEntity.created(URI.create(PATH + "/" + response.id())).body(response);
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
