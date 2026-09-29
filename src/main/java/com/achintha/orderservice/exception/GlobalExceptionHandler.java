package com.achintha.orderservice.exception;

import com.achintha.orderservice.exception.ApiError.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every error to the {@link ApiError} JSON shape. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request, List.of());
    }

    /** Unparseable path/query parameters, e.g. a non-UUID id. */
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(TypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + name + "'", request, List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", request, List.of());
    }

    /** Stock conflicts and invalid status transitions. */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    /** Two requests changed the same order at once (e.g. concurrent cancels); the loser gets a 409. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                  HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Order was modified concurrently, retry the request", request, List.of());
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    ResponseEntity<ApiError> handleProductServiceUnavailable(ProductServiceUnavailableException ex,
                                                             HttpServletRequest request) {
        log.warn("product-service unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getCause() != null ? ex.getCause().toString() : ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest request) {
        // Remaining Spring MVC exceptions (405, 415, missing params, ...) carry their own status code
        if (ex instanceof ErrorResponse errorResponse) {
            String detail = errorResponse.getBody().getDetail();
            return build(errorResponse.getStatusCode(), detail != null ? detail : ex.getMessage(), request, List.of());
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request, List.of());
    }

    private static ResponseEntity<ApiError> build(HttpStatusCode statusCode, String message,
                                                  HttpServletRequest request, List<FieldViolation> violations) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status != null ? status.getReasonPhrase() : String.valueOf(statusCode.value());
        ApiError body = new ApiError(Instant.now(), statusCode.value(), reason, message,
                request.getRequestURI(), violations);
        return ResponseEntity.status(statusCode).body(body);
    }
}
