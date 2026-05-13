package com.meli.challenge.dto;

import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;

import java.time.Instant;

public record AlertResponse(
    String id,
    String serviceId,
    AlertSeverity severity,
    String name,
    String description,
    AlertStatus status,
    Instant startedAt,
    String acknowledgedBy
) {
    public static AlertResponse from(Alert a) {
        return new AlertResponse(
            a.id(), a.serviceId(), a.severity(), a.name(),
            a.description(), a.status(), a.startedAt(), a.acknowledgedBy()
        );
    }
}
