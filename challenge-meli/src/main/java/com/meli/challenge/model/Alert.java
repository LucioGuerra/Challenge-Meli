package com.meli.challenge.model;

import java.time.Instant;

public record Alert(
    String id,
    String serviceId,
    AlertSeverity severity,
    String name,
    String description,
    AlertStatus status,
    Instant startedAt,
    String acknowledgedBy
) {}
