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
import com.rentflow.service.InventoryProtocolException;
import com.rentflow.service.InventoryServiceUnavailableException;
import com.rentflow.service.ReservationNotFoundException;

import tools.jackson.databind.exc.InvalidFormatException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Comparator<ViolationResponse> VIOLATION_ORDER =
            Comparator.comparing(ViolationResponse::field).thenComparing(ViolationResponse::message);

    @ExceptionHandler(ReservationNotFoundException.class)
    ResponseEntity<ProblemResponse> handleNotFound(ReservationNotFoundException exception, WebRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:reservation-not-found",
                "Reservation not found",
                "Reservation with ID '" + exception.getId() + "' was not found.",
                "RESERVATION_NOT_FOUND",
                request,
                List.of());
    }

    @ExceptionHandler(InventoryServiceUnavailableException.class)
    ResponseEntity<ProblemResponse> handleInventoryUnavailable(
            InventoryServiceUnavailableException exception, WebRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "urn:rentflow:problem:inventory-service-unavailable",
                "Inventory service unavailable",
                "Inventory is temporarily unavailable; retry this request with the same key.",
                "INVENTORY_SERVICE_UNAVAILABLE",
                request,
                List.of());
    }

    @ExceptionHandler(InventoryProtocolException.class)
    ResponseEntity<ProblemResponse> handleInventoryProtocol(InventoryProtocolException exception, WebRequest request) {
        return response(
                HttpStatus.BAD_GATEWAY,
                "urn:rentflow:problem:inventory-service-error",
                "Inventory service error",
                "Inventory returned an unexpected response.",
                "INVENTORY_SERVICE_ERROR",
                request,
                List.of());
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

        ProblemResponse problem = problem(
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "MALFORMED_JSON",
                request,
                List.of());
        return objectResponse(problem, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "urn:rentflow:problem:method-not-allowed",
                "Method not allowed",
                "The HTTP method is not supported for this resource.",
                "METHOD_NOT_ALLOWED",
                request,
                List.of());
        return objectResponse(problem, status, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(
                HttpStatus.NOT_ACCEPTABLE,
                "urn:rentflow:problem:not-acceptable",
                "Not acceptable",
                "No acceptable response representation is available.",
                "NOT_ACCEPTABLE",
                request,
                List.of());
        return objectResponse(problem, status, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemResponse problem = problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "urn:rentflow:problem:unsupported-media-type",
                "Unsupported media type",
                "The request media type is not supported.",
                "UNSUPPORTED_MEDIA_TYPE",
                request,
                List.of());
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
            ProblemResponse problem = problem(
                    status,
                    "urn:rentflow:problem:http-error",
                    "Request failed",
                    "The request could not be processed.",
                    "HTTP_ERROR",
                    request,
                    List.of());
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
        return problem(
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:validation-failed",
                "Request validation failed",
                "One or more request values are invalid.",
                "VALIDATION_FAILED",
                request,
                violations);
    }

    private ResponseEntity<ProblemResponse> response(
            HttpStatus status,
            String type,
            String title,
            String detail,
            String code,
            WebRequest request,
            List<ViolationResponse> violations) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, type, title, detail, code, request, violations));
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
        ProblemResponse problem = problem(
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:resource-not-found",
                "Resource not found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                request,
                List.of());
        return objectResponse(problem, status, headers);
    }

    private ProblemResponse internalError(WebRequest request) {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "urn:rentflow:problem:internal-error",
                "Internal server error",
                "An unexpected error occurred.",
                "INTERNAL_ERROR",
                request,
                List.of());
    }

    private ProblemResponse problem(
            HttpStatusCode status,
            String type,
            String title,
            String detail,
            String code,
            WebRequest request,
            List<ViolationResponse> violations) {
        return new ProblemResponse(type, title, status.value(), detail, requestPath(request), code, violations);
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
}
