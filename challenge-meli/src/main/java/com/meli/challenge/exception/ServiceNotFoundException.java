package com.meli.challenge.exception;

import org.springframework.http.HttpStatus;

public class ServiceNotFoundException extends ApiException {

    public ServiceNotFoundException(String serviceId) {
        super("SERVICE_NOT_FOUND", "Service with id '" + serviceId + "' not found", HttpStatus.NOT_FOUND);
    }
}
