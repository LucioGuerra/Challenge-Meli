package com.meli.challenge.dto;

import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;

import java.time.Instant;

public record ServiceDetailResponse(
    String id,
    String name,
    ServiceStatus status,
    String region,
    String version,
    Instant upSince,
    Instant lastDeployAt,
    String healthCheckUrl,
    String team
) {
    public static ServiceDetailResponse from(Service s) {
        return new ServiceDetailResponse(
            s.id(), s.name(), s.status(), s.region(), s.version(),
            s.upSince(), s.lastDeployAt(), s.healthCheckUrl(), s.team()
        );
    }
}
