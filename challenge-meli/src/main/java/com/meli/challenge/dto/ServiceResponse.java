package com.meli.challenge.dto;

import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;

import java.time.Instant;

public record ServiceResponse(
    String id,
    String name,
    ServiceStatus status,
    String region,
    String version,
    Instant upSince
) {
    public static ServiceResponse from(Service s) {
        return new ServiceResponse(
            s.id(), s.name(), s.status(), s.region(), s.version(), s.upSince()
        );
    }
}
