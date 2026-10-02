package com.achintha.orderservice.exception;

import org.springframework.http.HttpStatus;

/** 409: the request is valid but conflicts with the current state. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
