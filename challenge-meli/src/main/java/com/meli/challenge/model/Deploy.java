package com.meli.challenge.model;

import java.time.Instant;

public record Deploy(
    String id,
    String serviceId,
    String previousVersion,
    String newVersion,
    DeployStatus status,
    String deployedBy,
    Instant deployedAt,
    boolean rollbackAvailable
) {}
