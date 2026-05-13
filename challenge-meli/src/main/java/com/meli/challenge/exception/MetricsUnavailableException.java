package com.meli.challenge.exception;

import org.springframework.http.HttpStatus;

public class MetricsUnavailableException extends ApiException {

    public MetricsUnavailableException(String serviceId) {
        super("METRICS_UNAVAILABLE", "No metrics available for service '" + serviceId + "'", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
