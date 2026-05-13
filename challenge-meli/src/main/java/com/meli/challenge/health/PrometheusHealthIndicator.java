package com.meli.challenge.health;

import com.meli.challenge.client.MetricsClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("prometheus")
public class PrometheusHealthIndicator implements HealthIndicator {

    private final MetricsClient metricsClient;

    public PrometheusHealthIndicator(MetricsClient metricsClient) {
        this.metricsClient = metricsClient;
    }

    @Override
    public Health health() {
        try {
            return Health.up().withDetails(metricsClient.ping()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("source", "mock-prometheus").build();
        }
    }
}
