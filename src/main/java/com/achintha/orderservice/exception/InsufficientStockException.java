package com.achintha.orderservice.exception;

import org.springframework.http.HttpStatus;

/** 409: product-service cannot hold the requested quantity. */
public class InsufficientStockException extends ApiException {

    public InsufficientStockException(String message) {
        super(HttpStatus.CONFLICT, ErrorCode.INSUFFICIENT_STOCK, message);
    }
}
