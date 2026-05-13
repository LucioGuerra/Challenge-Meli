package com.meli.challenge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test that exercises the full Spring context: security,
 * filters, real InMemory clients, and the global exception handler. Catches
 * wiring bugs that the slice tests miss.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAndIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiV1_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MONITORING")
    void apiV1_wrongRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void apiV1_authorizedUser_returnsRealData() throws Exception {
        // hits real InMemoryServiceCatalogClient → ServiceCatalog (ordering applied)
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.meta.total").value(5))
            // search-service is DOWN, must be first after sort by status criticality
            .andExpect(jsonPath("$.data[0].id").value("search-service"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void slos_authorizedUser_breachedFirst() throws Exception {
        mockMvc.perform(get("/api/v1/slos"))
            .andExpect(status().isOk())
            // sorted by errorBudgetRemaining ASC → search-service (BREACHED, 0.0) first
            .andExpect(jsonPath("$.data[0].serviceId").value("search-service"))
            .andExpect(jsonPath("$.data[0].status").value("breached"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void alerts_authorizedUser_returnsCriticalFirst() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].severity").value("critical"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void getServiceById_existing_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/v1/services/payments-service"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("payments-service"))
            .andExpect(jsonPath("$.data.team").value("platform"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void getServiceById_missing_returns404WithCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/services/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SERVICE_NOT_FOUND"))
            .andExpect(jsonPath("$.error.correlationId").exists());
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void getMetrics_existingService_returnsWithSourcePrometheus() throws Exception {
        mockMvc.perform(get("/api/v1/services/payments-service/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.source").value("prometheus"))
            .andExpect(jsonPath("$.data.latency").exists());
    }

    @Test
    void healthLive_isPublic() throws Exception {
        mockMvc.perform(get("/health/live"))
            .andExpect(status().isOk());
    }

    @Test
    void healthReady_isPublicWithoutDetails() throws Exception {
        mockMvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "MONITORING")
    void healthReady_withMonitoringRole_exposesDependencies() throws Exception {
        mockMvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.argoCd").exists())
            .andExpect(jsonPath("$.components.prometheus").exists())
            .andExpect(jsonPath("$.components.alertmanager").exists());
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void response_includesCorrelationIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id",
                matchesPattern("^[0-9a-fA-F-]{36}$"))); // UUID
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void response_preservesIncomingCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/services").header("X-Correlation-Id", "trace-xyz"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "trace-xyz"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void unknownRoute_underApiNamespace_returns404WithErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "API_USER")
    void pagination_metaTotalReflectsFullDatasetNotPageSlice() throws Exception {
        // page=1, size=1 returns 1 element, but meta.total must still report 5 (total services)
        // This guards against the regression where total was computed from the paginated slice.
        mockMvc.perform(get("/api/v1/services").param("page", "1").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.meta.total").value(5))
            .andExpect(jsonPath("$.meta.page.page").value(1))
            .andExpect(jsonPath("$.meta.page.size").value(1))
            .andExpect(jsonPath("$.meta.page.totalPages").value(5));
    }
}
