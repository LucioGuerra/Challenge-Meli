package com.meli.challenge.health;

import com.meli.challenge.client.DeploymentClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("argoCd")
public class ArgoCdHealthIndicator implements HealthIndicator {

    private final DeploymentClient deploymentClient;

    public ArgoCdHealthIndicator(DeploymentClient deploymentClient) {
        this.deploymentClient = deploymentClient;
    }

    @Override
    public Health health() {
        try {
            return Health.up().withDetails(deploymentClient.ping()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("source", "mock-argocd").build();
        }
    }
}
