package com.meli.challenge.service;

import com.meli.challenge.client.MetricsClient;
import com.meli.challenge.exception.MetricsUnavailableException;
import com.meli.challenge.model.ServiceMetrics;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final MetricsClient metricsClient;
    private final ServiceCatalog serviceCatalog;

    public MetricsService(MetricsClient metricsClient, ServiceCatalog serviceCatalog) {
        this.metricsClient = metricsClient;
        this.serviceCatalog = serviceCatalog;
    }

    public ServiceMetrics getByServiceId(String serviceId) {
        serviceCatalog.findById(serviceId);
        return metricsClient.findByServiceId(serviceId)
            .orElseThrow(() -> new MetricsUnavailableException(serviceId));
    }
}
