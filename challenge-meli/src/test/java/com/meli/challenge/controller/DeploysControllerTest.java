package com.meli.challenge.controller;

import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;
import com.meli.challenge.service.DeploysService;
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

@WebMvcTest(DeploysController.class)
@AutoConfigureMockMvc(addFilters = false)
class DeploysControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeploysService deploysService;

    private final Deploy deploy = new Deploy("d-1", "svc-a", "1.0", "1.1",
        DeployStatus.SUCCESS, "ci", Instant.parse("2026-05-01T10:25:00Z"), true);

    @Test
    void list_returnsDeployEnvelope() throws Exception {
        when(deploysService.findAll(any(), any())).thenReturn(List.of(deploy));

        mockMvc.perform(get("/api/v1/deploys"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("d-1"))
            .andExpect(jsonPath("$.data[0].status").value("success"))
            .andExpect(jsonPath("$.data[0].rollbackAvailable").value(true))
            .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void list_invalidServiceId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/deploys").param("serviceId", "tiene espacios"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
