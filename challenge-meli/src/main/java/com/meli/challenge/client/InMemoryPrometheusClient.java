package com.meli.challenge.client;

import com.meli.challenge.model.ServiceMetrics;
import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class InMemoryPrometheusClient implements MetricsClient, SloClient {

    private final String prometheusUrl;

    public InMemoryPrometheusClient(@Value("${clients.prometheus.url}") String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    private final List<Slo> slos = List.of(
        new Slo("payments-service",      99.95, 99.97, SloStatus.MET,      72.5, "30d"),
        new Slo("auth-service",          99.95, 99.80, SloStatus.AT_RISK,  12.3, "30d"),
        new Slo("catalog-service",       99.95, 99.99, SloStatus.MET,      85.0, "30d"),
        new Slo("notifications-service", 99.90, 99.95, SloStatus.MET,      60.5, "30d"),
        new Slo("search-service",        99.95, 95.20, SloStatus.BREACHED,  0.0, "30d")
    );

    private final Map<String, ServiceMetrics> metricsByService = Map.of(
        "payments-service", new ServiceMetrics(
            "payments-service", "1h",
            new ServiceMetrics.Latency(45, 120, 350, "ms"),
            new ServiceMetrics.RequestRate(12500, 3.47),
            new ServiceMetrics.ErrorRate(23, 0.12, 0.04, "percent"),
            99.97
        ),
        "auth-service", new ServiceMetrics(
            "auth-service", "1h",
            new ServiceMetrics.Latency(30, 80, 200, "ms"),
            new ServiceMetrics.RequestRate(8500, 2.36),
            new ServiceMetrics.ErrorRate(420, 0.50, 4.44, "percent"),
            99.80
        ),
        "catalog-service", new ServiceMetrics(
            "catalog-service", "1h",
            new ServiceMetrics.Latency(20, 60, 150, "ms"),
            new ServiceMetrics.RequestRate(45000, 12.50),
            new ServiceMetrics.ErrorRate(15, 0.03, 0.01, "percent"),
            99.99
        ),
        "notifications-service", new ServiceMetrics(
            "notifications-service", "1h",
            new ServiceMetrics.Latency(80, 200, 500, "ms"),
            new ServiceMetrics.RequestRate(3200, 0.89),
            new ServiceMetrics.ErrorRate(8, 0.20, 0.05, "percent"),
            99.95
        ),
        "search-service", new ServiceMetrics(
            "search-service", "1h",
            new ServiceMetrics.Latency(0, 0, 0, "ms"),
            new ServiceMetrics.RequestRate(0, 0.0),
            new ServiceMetrics.ErrorRate(1500, 0.0, 100.0, "percent"),
            0.0
        )
    );

    @Override
    public List<Slo> findAll() {
        return slos;
    }

    @Override
    public Optional<ServiceMetrics> findByServiceId(String serviceId) {
        return Optional.ofNullable(metricsByService.get(serviceId));
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of(
            "source", "mock-prometheus",
            "endpoint", prometheusUrl,
            "version", "2.51.0",
            "scrapeTargets", metricsByService.size(),
            "slosTracked", slos.size(),
            "lastScrape", Instant.now().toString()
        );
    }
}
