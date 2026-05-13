package com.meli.challenge.client;

import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryDeploymentClient implements DeploymentClient {

    private final String argoCdUrl;

    public InMemoryDeploymentClient(@Value("${clients.argocd.url}") String argoCdUrl) {
        this.argoCdUrl = argoCdUrl;
    }

    private final List<Deploy> deploys = List.of(
        new Deploy("deploy-042", "payments-service", "2.4.0", "2.4.1",
            DeployStatus.SUCCESS, "ci/github-actions",
            Instant.parse("2026-05-01T10:25:00Z"), true),
        new Deploy("deploy-041", "catalog-service",  "3.0.0", "3.0.1",
            DeployStatus.SUCCESS, "ci/github-actions",
            Instant.parse("2026-05-02T13:55:00Z"), true),
        new Deploy("deploy-040", "auth-service",     "1.7.9", "1.8.0",
            DeployStatus.SUCCESS, "ci/github-actions",
            Instant.parse("2026-04-28T07:55:00Z"), true)
    );

    @Override
    public List<Deploy> findAll() {
        return deploys;
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of(
            "source", "mock-argocd",
            "endpoint", argoCdUrl,
            "version", "2.9.3",
            "deploymentsTracked", deploys.size(),
            "lastSync", Instant.now().toString()
        );
    }
}
