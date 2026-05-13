package com.meli.challenge.service;

import com.meli.challenge.client.ServiceCatalogClient;
import com.meli.challenge.exception.ServiceNotFoundException;
import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceCatalogTest {

    @Mock
    private ServiceCatalogClient client;

    @InjectMocks
    private ServiceCatalog catalog;

    private final Instant now = Instant.parse("2026-05-01T00:00:00Z");

    private Service svc(String id, String name, ServiceStatus status) {
        return new Service(id, name, status, "us-east-1", "1.0.0", now, now, "/health/ready", "platform");
    }

    private final Service upAlpha     = svc("a", "Alpha",   ServiceStatus.UP);
    private final Service upBeta      = svc("b", "Beta",    ServiceStatus.UP);
    private final Service degraded    = svc("c", "Charlie", ServiceStatus.DEGRADED);
    private final Service down        = svc("d", "Delta",   ServiceStatus.DOWN);
    private final Service unknown     = svc("e", "Echo",    ServiceStatus.UNKNOWN);

    @Test
    void findAll_noFilters_sortsByStatusPriorityThenName() {
        when(client.findAll()).thenReturn(List.of(upBeta, unknown, upAlpha, down, degraded));
        List<Service> result = catalog.findAll(null, null);
        assertThat(result).extracting(Service::id).containsExactly("d", "c", "a", "b", "e");
    }

    @Test
    void findAll_filterByStatus_returnsOnlyMatching() {
        when(client.findAll()).thenReturn(List.of(upBeta, upAlpha, down, degraded));
        List<Service> result = catalog.findAll(ServiceStatus.UP, null);
        assertThat(result).extracting(Service::id).containsExactly("a", "b");
    }

    @Test
    void findAll_filterByName_isCaseInsensitiveSubstring() {
        when(client.findAll()).thenReturn(List.of(upAlpha, upBeta, degraded));
        List<Service> result = catalog.findAll(null, "AL");
        assertThat(result).extracting(Service::name).containsExactly("Alpha");
    }

    @Test
    void findById_existing_returnsService() {
        when(client.findById("a")).thenReturn(Optional.of(upAlpha));
        Service result = catalog.findById("a");
        assertThat(result.name()).isEqualTo("Alpha");
    }

    @Test
    void findById_missing_throwsServiceNotFoundException() {
        when(client.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalog.findById("missing"))
            .isInstanceOf(ServiceNotFoundException.class)
            .hasMessageContaining("missing");
    }
}
