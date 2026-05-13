package com.meli.challenge.service;

import com.meli.challenge.client.DeploymentClient;
import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploysServiceTest {

    @Mock
    private DeploymentClient deploymentClient;

    @InjectMocks
    private DeploysService service;

    private final Deploy oldest = new Deploy("d-1", "svc-a", "1.0", "1.1",
        DeployStatus.SUCCESS, "ci", Instant.parse("2026-04-28T07:55:00Z"), true);
    private final Deploy middle = new Deploy("d-2", "svc-a", "1.1", "1.2",
        DeployStatus.FAILED, "ci", Instant.parse("2026-05-01T10:25:00Z"), true);
    private final Deploy newest = new Deploy("d-3", "svc-b", "3.0", "3.1",
        DeployStatus.SUCCESS, "ci", Instant.parse("2026-05-02T13:55:00Z"), true);

    @BeforeEach
    void setupClient() {
        when(deploymentClient.findAll()).thenReturn(List.of(oldest, newest, middle));
    }

    @Test
    void findAll_noFilters_sortsByDeployedAtDescending() {
        List<Deploy> result = service.findAll(null, null);
        assertThat(result).extracting(Deploy::id).containsExactly("d-3", "d-2", "d-1");
    }

    @Test
    void findAll_filterByStatus_returnsOnlyMatching() {
        List<Deploy> result = service.findAll(DeployStatus.FAILED, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("d-2");
    }

    @Test
    void findAll_filterByServiceId_keepsDescendingOrder() {
        List<Deploy> result = service.findAll(null, "svc-a");
        assertThat(result).extracting(Deploy::id).containsExactly("d-2", "d-1");
    }
}
