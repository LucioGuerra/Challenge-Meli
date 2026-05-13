package com.meli.challenge.client;

import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryAlertingClient implements AlertingClient {

    private final String alertmanagerUrl;

    public InMemoryAlertingClient(@Value("${clients.alertmanager.url}") String alertmanagerUrl) {
        this.alertmanagerUrl = alertmanagerUrl;
    }

    private final List<Alert> alerts = List.of(
        new Alert("alert-001", "auth-service",          AlertSeverity.CRITICAL,
            "HighErrorRate",       "Error rate above 5% for last 10 minutes",
            AlertStatus.FIRING,       Instant.parse("2026-05-06T13:45:00Z"), null),
        new Alert("alert-002", "search-service",        AlertSeverity.CRITICAL,
            "ServiceDown",         "Service has been unreachable for 30+ minutes",
            AlertStatus.FIRING,       Instant.parse("2026-04-25T18:00:00Z"), null),
        new Alert("alert-003", "notifications-service", AlertSeverity.MEDIUM,
            "HighLatency",         "P99 latency above 500ms",
            AlertStatus.ACKNOWLEDGED, Instant.parse("2026-05-06T12:30:00Z"), "alice@meli.com"),
        new Alert("alert-004", "payments-service",      AlertSeverity.LOW,
            "ElevatedErrorRate",   "4xx error rate slightly elevated",
            AlertStatus.RESOLVED,     Instant.parse("2026-05-05T08:00:00Z"), "bob@meli.com")
    );

    @Override
    public List<Alert> findAll() {
        return alerts;
    }

    @Override
    public Map<String, Object> ping() {
        long firing = alerts.stream().filter(a -> a.status() == AlertStatus.FIRING).count();
        return Map.of(
            "source", "mock-alertmanager",
            "endpoint", alertmanagerUrl,
            "version", "0.27.0",
            "alertsTotal", alerts.size(),
            "alertsFiring", firing,
            "lastCheck", Instant.now().toString()
        );
    }
}
