package com.achintha.orderservice.exception;

/**
 * product-service could not be reached: it is down, timed out, returned a server error, or its circuit breaker is
 * open. Mapped to 503 so callers can tell "try again later" apart from a client error.
 */
public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
