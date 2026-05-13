package com.meli.challenge.controller;

import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;
import com.meli.challenge.service.AlertsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AlertsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertsService alertsService;

    private final Alert critical = new Alert("a-1", "svc-a", AlertSeverity.CRITICAL,
        "ServiceDown", "down", AlertStatus.FIRING,
        Instant.parse("2026-04-25T18:00:00Z"), null);

    @Test
    void list_returnsAlertEnvelope() throws Exception {
        when(alertsService.findAll(any(), any(), any())).thenReturn(List.of(critical));

        mockMvc.perform(get("/api/v1/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("a-1"))
            .andExpect(jsonPath("$.data[0].severity").value("critical"))
            .andExpect(jsonPath("$.data[0].status").value("firing"))
            .andExpect(jsonPath("$.data[0].acknowledgedBy").doesNotExist())
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void list_passesFiltersToService() throws Exception {
        when(alertsService.findAll(eq(AlertSeverity.CRITICAL), eq(AlertStatus.FIRING), eq("svc-a")))
            .thenReturn(List.of(critical));

        mockMvc.perform(get("/api/v1/alerts")
                .param("severity", "CRITICAL")
                .param("status", "FIRING")
                .param("serviceId", "svc-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void list_unknownSeverity_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").param("severity", "nope"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }
}
