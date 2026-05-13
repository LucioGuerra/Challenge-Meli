package com.meli.challenge.client;

import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class InMemoryServiceCatalogClient implements ServiceCatalogClient {

    private final List<Service> services = List.of(
        new Service("payments-service",      "Payments Service",      ServiceStatus.UP,       "us-east-1", "2.4.1",
            Instant.parse("2026-05-01T10:30:00Z"), Instant.parse("2026-05-01T10:25:00Z"), "/health/ready", "platform"),
        new Service("auth-service",          "Auth Service",          ServiceStatus.DEGRADED, "us-east-1", "1.8.0",
            Instant.parse("2026-04-28T08:00:00Z"), Instant.parse("2026-04-28T07:55:00Z"), "/health/ready", "platform"),
        new Service("catalog-service",       "Catalog Service",       ServiceStatus.UP,       "us-east-1", "3.0.1",
            Instant.parse("2026-05-02T14:00:00Z"), Instant.parse("2026-05-02T13:55:00Z"), "/health/ready", "search"),
        new Service("notifications-service", "Notifications Service", ServiceStatus.UP,       "us-west-2", "1.2.0",
            Instant.parse("2026-04-30T09:15:00Z"), Instant.parse("2026-04-30T09:10:00Z"), "/health/ready", "messaging"),
        new Service("search-service",        "Search Service",        ServiceStatus.DOWN,     "us-east-1", "4.2.0",
            Instant.parse("2026-04-15T12:00:00Z"), Instant.parse("2026-04-25T18:00:00Z"), "/health/ready", "search")
    );

    @Override
    public List<Service> findAll() {
        return services;
    }

    @Override
    public Optional<Service> findById(String id) {
        return services.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of(
            "source", "mock-service-catalog",
            "endpoint", "https://consul.mock.meli/v1/catalog",
            "version", "1.17.0",
            "servicesTracked", services.size(),
            "lastSync", Instant.now().toString()
        );
    }
}
