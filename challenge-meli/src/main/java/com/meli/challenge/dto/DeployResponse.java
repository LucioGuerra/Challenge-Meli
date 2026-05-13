package com.meli.challenge.dto;

import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;

import java.time.Instant;

public record DeployResponse(
    String id,
    String serviceId,
    String previousVersion,
    String newVersion,
    DeployStatus status,
    String deployedBy,
    Instant deployedAt,
    boolean rollbackAvailable
) {
    public static DeployResponse from(Deploy d) {
        return new DeployResponse(
            d.id(), d.serviceId(), d.previousVersion(), d.newVersion(),
            d.status(), d.deployedBy(), d.deployedAt(), d.rollbackAvailable()
        );
    }
}
