package com.achintha.orderservice.exception;

/** An order status transition that the lifecycle rules don't allow, e.g. cancelling a FAILED order. */
public class InvalidOrderStateException extends ConflictException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
