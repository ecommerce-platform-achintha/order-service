package com.achintha.orderservice.exception;

import org.springframework.http.HttpStatus;

/** 409: the action is not allowed in the order's current status (section 6.2). */
public class InvalidOrderStateException extends ApiException {

    public InvalidOrderStateException(String message) {
        super(HttpStatus.CONFLICT, ErrorCode.INVALID_ORDER_STATE, message);
    }
}
