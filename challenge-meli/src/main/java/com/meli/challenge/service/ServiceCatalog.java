package com.meli.challenge.service;

import com.meli.challenge.client.ServiceCatalogClient;
import com.meli.challenge.exception.ServiceNotFoundException;
import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@org.springframework.stereotype.Service
public class ServiceCatalog {

    // DOWN and DEGRADED surface first so operators see unhealthy services at the top
    private static final Map<ServiceStatus, Integer> STATUS_PRIORITY = Map.of(
        ServiceStatus.DOWN,     0,
        ServiceStatus.DEGRADED, 1,
        ServiceStatus.UP,       2,
        ServiceStatus.UNKNOWN,  3
    );

    private final ServiceCatalogClient catalogClient;

    public ServiceCatalog(ServiceCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    public List<Service> findAll(ServiceStatus status, String name) {
        String search = name == null ? null : name.toLowerCase();
        return catalogClient.findAll().stream()
            .filter(s -> status == null || s.status() == status)
            .filter(s -> search == null || s.name().toLowerCase().contains(search))
            .sorted(Comparator.comparingInt((Service s) -> STATUS_PRIORITY.get(s.status()))
                .thenComparing(Service::name))
            .toList();
    }

    public Service findById(String id) {
        return catalogClient.findById(id)
            .orElseThrow(() -> new ServiceNotFoundException(id));
    }
}
