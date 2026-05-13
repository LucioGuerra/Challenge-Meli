package com.meli.challenge.health;

import com.meli.challenge.client.AlertingClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("alertmanager")
public class AlertmanagerHealthIndicator implements HealthIndicator {

    private final AlertingClient alertingClient;

    public AlertmanagerHealthIndicator(AlertingClient alertingClient) {
        this.alertingClient = alertingClient;
    }

    @Override
    public Health health() {
        try {
            return Health.up().withDetails(alertingClient.ping()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("source", "mock-alertmanager").build();
        }
    }
}
