package com.achintha.orderservice.exception;

import org.springframework.http.HttpStatus;

/**
 * 503: another service (product-, store- or user-service) could not answer: it is down, timed out, returned a server
 * error, or its circuit breaker is open. Nothing is fabricated in its place.
 */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, message);
        initCause(cause);
    }
}
