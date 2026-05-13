package com.meli.challenge.service;

import com.meli.challenge.client.DeploymentClient;
import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class DeploysService {

    private final DeploymentClient deploymentClient;

    public DeploysService(DeploymentClient deploymentClient) {
        this.deploymentClient = deploymentClient;
    }

    public List<Deploy> findAll(DeployStatus status, String serviceId) {
        return deploymentClient.findAll().stream()
            .filter(d -> status == null || d.status() == status)
            .filter(d -> serviceId == null || d.serviceId().equals(serviceId))
            .sorted(Comparator.comparing(Deploy::deployedAt).reversed())
            .toList();
    }
}
