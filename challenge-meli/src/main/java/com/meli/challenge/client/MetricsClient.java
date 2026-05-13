package com.meli.challenge.client;

import com.meli.challenge.model.ServiceMetrics;

import java.util.Map;
import java.util.Optional;

public interface MetricsClient {

    Optional<ServiceMetrics> findByServiceId(String serviceId);

    Map<String, Object> ping();
}
