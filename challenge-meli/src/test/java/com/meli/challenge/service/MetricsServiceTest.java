package com.meli.challenge.service;

import com.meli.challenge.client.MetricsClient;
import com.meli.challenge.exception.MetricsUnavailableException;
import com.meli.challenge.exception.ServiceNotFoundException;
import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceMetrics;
import com.meli.challenge.model.ServiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock
    private MetricsClient metricsClient;

    @Mock
    private ServiceCatalog serviceCatalog;

    @InjectMocks
    private MetricsService metricsService;

    private final ServiceMetrics metrics = new ServiceMetrics(
        "svc-a", "1h",
        new ServiceMetrics.Latency(50, 120, 200, "ms"),
        new ServiceMetrics.RequestRate(1000, 16.7),
        new ServiceMetrics.ErrorRate(10, 0.5, 0.1, "percent"),
        99.95
    );

    private final Service svc = new Service(
        "svc-a", "Alpha", ServiceStatus.UP, "us-east-1", "1.0.0",
        Instant.now(), Instant.now(), "/health/ready", "platform"
    );

    @Test
    void getByServiceId_existingServiceWithMetrics_returnsMetrics() {
        when(serviceCatalog.findById("svc-a")).thenReturn(svc);
        when(metricsClient.findByServiceId("svc-a")).thenReturn(Optional.of(metrics));

        ServiceMetrics result = metricsService.getByServiceId("svc-a");

        assertThat(result.serviceId()).isEqualTo("svc-a");
        assertThat(result.availability()).isEqualTo(99.95);
    }

    @Test
    void getByServiceId_unknownService_propagatesServiceNotFound() {
        when(serviceCatalog.findById("missing"))
            .thenThrow(new ServiceNotFoundException("missing"));

        assertThatThrownBy(() -> metricsService.getByServiceId("missing"))
            .isInstanceOf(ServiceNotFoundException.class);
    }

    @Test
    void getByServiceId_existingServiceWithoutMetrics_throwsMetricsUnavailable() {
        when(serviceCatalog.findById("svc-a")).thenReturn(svc);
        when(metricsClient.findByServiceId("svc-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricsService.getByServiceId("svc-a"))
            .isInstanceOf(MetricsUnavailableException.class)
            .hasMessageContaining("svc-a");
    }
}
