package com.achintha.orderservice.exception;

import org.springframework.http.HttpStatus;

/** 404. Also used for ids that exist but belong to someone else (section 3.4: never 403 for a foreign id). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(ErrorCode code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
