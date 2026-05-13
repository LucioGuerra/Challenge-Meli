package com.meli.challenge.service;

import com.meli.challenge.client.AlertingClient;
import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class AlertsService {

    private final AlertingClient alertingClient;

    public AlertsService(AlertingClient alertingClient) {
        this.alertingClient = alertingClient;
    }

    public List<Alert> findAll(AlertSeverity severity, AlertStatus status, String serviceId) {
        return alertingClient.findAll().stream()
            .filter(a -> severity == null || a.severity() == severity)
            .filter(a -> status == null || a.status() == status)
            .filter(a -> serviceId == null || a.serviceId().equals(serviceId))
            .sorted(Comparator.comparingInt((Alert a) -> a.severity().ordinal())
                .thenComparing(Comparator.comparing(Alert::startedAt).reversed()))
            .toList();
    }
}
