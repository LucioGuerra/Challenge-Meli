package com.meli.challenge.dto;

import com.meli.challenge.model.ServiceMetrics;

public record ServiceMetricsResponse(
    String serviceId,
    String window,
    ServiceMetrics.Latency latency,
    ServiceMetrics.RequestRate requestRate,
    ServiceMetrics.ErrorRate errorRate,
    double availability
) {
    public static ServiceMetricsResponse from(ServiceMetrics m) {
        return new ServiceMetricsResponse(
            m.serviceId(), m.window(),
            m.latency(), m.requestRate(), m.errorRate(),
            m.availability()
        );
    }
}
