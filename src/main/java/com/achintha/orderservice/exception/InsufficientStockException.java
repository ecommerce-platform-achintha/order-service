package com.achintha.orderservice.exception;

import java.util.UUID;

/** Requested quantity exceeds what product-service reports as available. */
public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(UUID productId, int requested, int available) {
        super("Insufficient stock for product " + productId + ": requested " + requested + ", available "
                + available);
    }

    public InsufficientStockException(String message) {
        super(message);
    }
}
