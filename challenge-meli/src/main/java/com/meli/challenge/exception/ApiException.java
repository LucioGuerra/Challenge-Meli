package com.meli.challenge.exception;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    protected ApiException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
