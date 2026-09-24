package com.rentflow.controller;

import java.util.Comparator;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ViolationResponse;
import com.rentflow.service.InventoryGateway.ProtocolException;
import com.rentflow.service.InventoryGateway.ServiceUnavailableException;
import com.rentflow.service.ReservationNotFoundException;

import tools.jackson.databind.exc.InvalidFormatException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Comparator<ViolationResponse> VIOLATION_ORDER =
            Comparator.comparing(ViolationResponse::field).thenComparing(ViolationResponse::message);

    @ExceptionHandler(ReservationNotFoundException.class)
    ResponseEntity<ProblemResponse> handleNotFound(ReservationNotFoundException exception, WebRequest request) {
        return response(
                ApiProblem.RESERVATION_NOT_FOUND,
                "Reservation with ID '" + exception.getId() + "' was not found.",
                request,
                List.of());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemResponse> handleInventoryUnavailable(
            ServiceUnavailableException exception, WebRequest request) {
        return response(ApiProblem.INVENTORY_SERVICE_UNAVAILABLE, request, List.of());
    }

    @ExceptionHandler(ProtocolException.class)
    ResponseEntity<ProblemResponse> handleInventoryProtocol(ProtocolException exception, WebRequest request) {
        return response(ApiProblem.INVENTORY_SERVICE_ERROR, request, List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemResponse> handleConstraintViolation(
            ConstraintViolationException exception, WebRequest request) {
        List<ViolationResponse> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ViolationResponse(
                        finalPathSegment(violation.getPropertyPath().toString()), violation.getMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return validationResponse(violations, request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ProblemResponse> handleRequestValidation(RequestValidationException exception, WebRequest request) {
        return validationResponse(
                exception.getViolations().stream().sorted(VIOLATION_ORDER).toList(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ViolationResponse> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ViolationResponse(error.getField(), error.getDefaultMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ViolationResponse> violations = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ViolationResponse(parameterName(result), message(error))))
                .sorted(VIOLATION_ORDER)
                .toList();
        return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = exception.getPropertyName() == null ? "request" : exception.getPropertyName();
        List<ViolationResponse> violations = List.of(new ViolationResponse(field, "must have a valid value"));
        return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        InvalidFormatException invalidFormat = findCause(exception, InvalidFormatException.class);
        if (invalidFormat != null) {
            List<ViolationResponse> violations =
                    List.of(new ViolationResponse(invalidField(invalidFormat), "must have a valid value"));
            return objectResponse(validationProblem(violations, request), HttpStatus.BAD_REQUEST);
        }

        ProblemResponse problem = problem(ApiProblem.MALFORMED_JSON, request, List.of());
        return objectResponse(problem, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(ApiProblem.METHOD_NOT_ALLOWED, request, List.of());
        return objectResponse(problem, status, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(ApiProblem.NOT_ACCEPTABLE, request, List.of());
        return objectResponse(problem, status, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(ApiProblem.UNSUPPORTED_MEDIA_TYPE, request, List.of());
        return objectResponse(problem, status, headers);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return resourceNotFound(status, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return resourceNotFound(status, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is4xxClientError()) {
            ProblemResponse problem = problem(status, ApiProblem.HTTP_ERROR, request, List.of());
            return objectResponse(problem, status, headers);
        }
        return objectResponse(internalError(request), HttpStatus.INTERNAL_SERVER_ERROR, headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemResponse> handleUnexpected(Exception exception, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        logger.error("Unexpected failure handling "
                + servletRequest.getMethod()
                + " "
                + servletRequest.getRequestURI()
                + " ("
                + exception.getClass().getName()
                + ")");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(internalError(request));
    }

    private ResponseEntity<ProblemResponse> validationResponse(List<ViolationResponse> violations, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(validationProblem(violations, request));
    }

    private ProblemResponse validationProblem(List<ViolationResponse> violations, WebRequest request) {
        return problem(ApiProblem.VALIDATION_FAILED, request, violations);
    }

    private ResponseEntity<ProblemResponse> response(
            ApiProblem problem, WebRequest request, List<ViolationResponse> violations) {
        return response(problem, problem.detail(), request, violations);
    }

    private ResponseEntity<ProblemResponse> response(
            ApiProblem problem, String detail, WebRequest request, List<ViolationResponse> violations) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(problem, detail, request, violations));
    }

    private ResponseEntity<Object> objectResponse(ProblemResponse problem, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ResponseEntity<Object> objectResponse(ProblemResponse problem, HttpStatusCode status, HttpHeaders headers) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ResponseEntity<Object> resourceNotFound(HttpStatusCode status, HttpHeaders headers, WebRequest request) {
        ProblemResponse problem = problem(ApiProblem.RESOURCE_NOT_FOUND, request, List.of());
        return objectResponse(problem, status, headers);
    }

    private ProblemResponse internalError(WebRequest request) {
        return problem(ApiProblem.INTERNAL_ERROR, request, List.of());
    }

    private ProblemResponse problem(ApiProblem problem, WebRequest request, List<ViolationResponse> violations) {
        return problem(problem, problem.detail(), request, violations);
    }

    private ProblemResponse problem(
            ApiProblem problem, String detail, WebRequest request, List<ViolationResponse> violations) {
        return new ProblemResponse(
                problem.type(),
                problem.title(),
                problem.status().value(),
                detail,
                requestPath(request),
                problem.code(),
                violations);
    }

    private ProblemResponse problem(
            HttpStatusCode status, ApiProblem problem, WebRequest request, List<ViolationResponse> violations) {
        return new ProblemResponse(
                problem.type(),
                problem.title(),
                status.value(),
                problem.detail(),
                requestPath(request),
                problem.code(),
                violations);
    }

    private String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }

    private String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name == null ? "request" : name;
    }

    private String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? "is invalid" : message;
    }

    private String finalPathSegment(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private String invalidField(InvalidFormatException exception) {
        if (exception.getPath().isEmpty()) {
            return "request";
        }
        String field = exception.getPath().get(exception.getPath().size() - 1).getPropertyName();
        return field == null ? "request" : field;
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private enum ApiProblem {
        RESERVATION_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                "reservation-not-found",
                "Reservation not found",
                "Reservation was not found.",
                "RESERVATION_NOT_FOUND"),
        INVENTORY_SERVICE_UNAVAILABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                "inventory-service-unavailable",
                "Inventory service unavailable",
                "Inventory is temporarily unavailable; retry this request with the same key.",
                "INVENTORY_SERVICE_UNAVAILABLE"),
        INVENTORY_SERVICE_ERROR(
                HttpStatus.BAD_GATEWAY,
                "inventory-service-error",
                "Inventory service error",
                "Inventory returned an unexpected response.",
                "INVENTORY_SERVICE_ERROR"),
        VALIDATION_FAILED(
                HttpStatus.BAD_REQUEST,
                "validation-failed",
                "Request validation failed",
                "One or more request values are invalid.",
                "VALIDATION_FAILED"),
        MALFORMED_JSON(
                HttpStatus.BAD_REQUEST,
                "malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "MALFORMED_JSON"),
        METHOD_NOT_ALLOWED(
                HttpStatus.METHOD_NOT_ALLOWED,
                "method-not-allowed",
                "Method not allowed",
                "The HTTP method is not supported for this resource.",
                "METHOD_NOT_ALLOWED"),
        NOT_ACCEPTABLE(
                HttpStatus.NOT_ACCEPTABLE,
                "not-acceptable",
                "Not acceptable",
                "No acceptable response representation is available.",
                "NOT_ACCEPTABLE"),
        UNSUPPORTED_MEDIA_TYPE(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported-media-type",
                "Unsupported media type",
                "The request media type is not supported.",
                "UNSUPPORTED_MEDIA_TYPE"),
        RESOURCE_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                "resource-not-found",
                "Resource not found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND"),
        HTTP_ERROR(
                HttpStatus.BAD_REQUEST,
                "http-error",
                "Request failed",
                "The request could not be processed.",
                "HTTP_ERROR"),
        INTERNAL_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal server error",
                "An unexpected error occurred.",
                "INTERNAL_ERROR");

        private final HttpStatus status;
        private final String type;
        private final String title;
        private final String detail;
        private final String code;

        ApiProblem(HttpStatus status, String typeSuffix, String title, String detail, String code) {
            this.status = status;
            this.type = "urn:rentflow:problem:" + typeSuffix;
            this.title = title;
            this.detail = detail;
            this.code = code;
        }

        HttpStatus status() {
            return status;
        }

        String type() {
            return type;
        }

        String title() {
            return title;
        }

        String detail() {
            return detail;
        }

        String code() {
            return code;
        }
    }
}
