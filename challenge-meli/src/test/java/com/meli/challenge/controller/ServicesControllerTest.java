package com.meli.challenge.controller;

import com.meli.challenge.exception.MetricsUnavailableException;
import com.meli.challenge.exception.ServiceNotFoundException;
import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceMetrics;
import com.meli.challenge.model.ServiceStatus;
import com.meli.challenge.service.MetricsService;
import com.meli.challenge.service.ServiceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ServicesController.class)
@AutoConfigureMockMvc(addFilters = false)
class ServicesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceCatalog serviceCatalog;

    @MockitoBean
    private MetricsService metricsService;

    private final Instant fixed = Instant.parse("2026-05-01T10:30:00Z");

    private final Service svc = new Service(
        "payments-service", "Payments Service", ServiceStatus.UP, "us-east-1", "2.4.1",
        fixed, fixed, "/health/ready", "platform"
    );

    private final ServiceMetrics metrics = new ServiceMetrics(
        "payments-service", "1h",
        new ServiceMetrics.Latency(50, 120, 200, "ms"),
        new ServiceMetrics.RequestRate(1000, 16.7),
        new ServiceMetrics.ErrorRate(10, 0.5, 0.1, "percent"),
        99.95
    );

    @Test
    void list_returnsServiceListEnvelope() throws Exception {
        when(serviceCatalog.findAll(any(), any())).thenReturn(List.of(svc));

        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("payments-service"))
            .andExpect(jsonPath("$.data[0].status").value("UP"))
            .andExpect(jsonPath("$.data[0].team").doesNotExist()) // ServiceResponse no incluye team
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void getById_returnsDetailWithTeamAndHealthCheckUrl() throws Exception {
        when(serviceCatalog.findById("payments-service")).thenReturn(svc);

        mockMvc.perform(get("/api/v1/services/payments-service"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("payments-service"))
            .andExpect(jsonPath("$.data.team").value("platform"))
            .andExpect(jsonPath("$.data.healthCheckUrl").value("/health/ready"));
    }

    @Test
    void getById_unknown_returns404WithErrorCode() throws Exception {
        when(serviceCatalog.findById("missing"))
            .thenThrow(new ServiceNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/services/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SERVICE_NOT_FOUND"))
            .andExpect(jsonPath("$.error.path").value("/api/v1/services/missing"));
    }

    @Test
    void getById_invalidIdPattern_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/services/has spaces"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getMetrics_returnsMetricsWithSourcePrometheus() throws Exception {
        when(metricsService.getByServiceId("payments-service")).thenReturn(metrics);

        mockMvc.perform(get("/api/v1/services/payments-service/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.serviceId").value("payments-service"))
            .andExpect(jsonPath("$.data.latency.p99").value(200))
            .andExpect(jsonPath("$.data.availability").value(99.95))
            .andExpect(jsonPath("$.meta.source").value("prometheus"));
    }

    @Test
    void getMetrics_metricsUnavailable_returns503() throws Exception {
        when(metricsService.getByServiceId("payments-service"))
            .thenThrow(new MetricsUnavailableException("payments-service"));

        mockMvc.perform(get("/api/v1/services/payments-service/metrics"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("METRICS_UNAVAILABLE"));
    }

    @Test
    void getById_unexpectedRuntimeException_returns500WithInternalErrorCode() throws Exception {
        // Any unhandled RuntimeException must hit the catch-all @ExceptionHandler(Exception.class)
        // and surface as a structured 500 INTERNAL_ERROR — never leak stacktraces or empty bodies.
        when(serviceCatalog.findById("boom"))
            .thenThrow(new RuntimeException("unexpected downstream failure"));

        mockMvc.perform(get("/api/v1/services/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.status").value(500))
            .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.error.path").value("/api/v1/services/boom"));
    }
}
