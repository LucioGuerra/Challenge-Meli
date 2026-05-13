package com.meli.challenge.exception;

import org.springframework.http.HttpStatus;

public class InvalidQueryParameterException extends ApiException {

    public InvalidQueryParameterException(String paramName, String rejectedValue) {
        super("INVALID_PARAMETER",
            "Parameter '" + paramName + "' has invalid value: '" + rejectedValue + "'",
            HttpStatus.BAD_REQUEST);
    }
}
