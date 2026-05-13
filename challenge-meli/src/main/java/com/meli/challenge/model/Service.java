package com.meli.challenge.model;

import java.time.Instant;

public record Service(
    String id,
    String name,
    ServiceStatus status,
    String region,
    String version,
    Instant upSince,
    Instant lastDeployAt,
    String healthCheckUrl,
    String team
) {}
