package com.meli.challenge.controller;

import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;
import com.meli.challenge.service.SlosService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlosController.class)
@AutoConfigureMockMvc(addFilters = false)
class SlosControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlosService slosService;

    private final Slo breached = new Slo("svc-a", 99.95, 95.0, SloStatus.BREACHED, 0.0, "30d");
    private final Slo met      = new Slo("svc-b", 99.95, 99.99, SloStatus.MET, 85.0, "30d");

    @Test
    void list_returnsPaginatedEnvelopeWithDataAndMeta() throws Exception {
        when(slosService.findAll(any(), any())).thenReturn(List.of(breached, met));

        mockMvc.perform(get("/api/v1/slos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].serviceId").value("svc-a"))
            .andExpect(jsonPath("$.data[0].status").value("breached"))
            .andExpect(jsonPath("$.meta.total").value(2))
            .andExpect(jsonPath("$.meta.page.page").value(0))
            .andExpect(jsonPath("$.meta.page.size").value(20))
            .andExpect(jsonPath("$.meta.page.totalPages").value(1));
    }

    @Test
    void list_appliesPagination_returnsOnlyRequestedSlice() throws Exception {
        when(slosService.findAll(any(), any())).thenReturn(List.of(breached, met));

        mockMvc.perform(get("/api/v1/slos").param("page", "0").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].serviceId").value("svc-a"))
            .andExpect(jsonPath("$.meta.total").value(2))
            .andExpect(jsonPath("$.meta.page.totalPages").value(2));
    }

    @Test
    void list_invalidServiceIdPattern_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/slos").param("serviceId", "invalid id"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void list_unknownStatusEnum_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/slos").param("status", "NOT_A_STATUS"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void list_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/slos").param("page", "-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void list_sizeAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/slos").param("size", "500"))
            .andExpect(status().isBadRequest());
    }
}
