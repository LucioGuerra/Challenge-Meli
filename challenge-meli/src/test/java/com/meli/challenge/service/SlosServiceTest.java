package com.meli.challenge.service;

import com.meli.challenge.client.SloClient;
import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlosServiceTest {

    @Mock
    private SloClient sloClient;

    @InjectMocks
    private SlosService service;

    private final Slo breached  = new Slo("svc-a", 99.95,  95.00, SloStatus.BREACHED,  0.0, "30d");
    private final Slo atRisk    = new Slo("svc-b", 99.95,  99.80, SloStatus.AT_RISK,  12.3, "30d");
    private final Slo metMid    = new Slo("svc-c", 99.95,  99.97, SloStatus.MET,      72.5, "30d");
    private final Slo metHigh   = new Slo("svc-d", 99.95,  99.99, SloStatus.MET,      85.0, "30d");

    @BeforeEach
    void setupClient() {
        when(sloClient.findAll()).thenReturn(List.of(metMid, metHigh, breached, atRisk));
    }

    @Test
    void findAll_noFilters_sortsByErrorBudgetAscending() {
        List<Slo> result = service.findAll(null, null);
        assertThat(result).extracting(Slo::serviceId)
            .containsExactly("svc-a", "svc-b", "svc-c", "svc-d");
    }

    @Test
    void findAll_filterByStatus_returnsOnlyMatching() {
        List<Slo> result = service.findAll(SloStatus.MET, null);
        assertThat(result).extracting(Slo::serviceId).containsExactly("svc-c", "svc-d");
    }

    @Test
    void findAll_filterByServiceId_returnsExactMatch() {
        List<Slo> result = service.findAll(null, "svc-a");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(SloStatus.BREACHED);
    }

    @Test
    void findAll_combinedFilters_returnsIntersection() {
        List<Slo> result = service.findAll(SloStatus.MET, "svc-d");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).serviceId()).isEqualTo("svc-d");
    }

    @Test
    void findAll_noMatch_returnsEmpty() {
        List<Slo> result = service.findAll(SloStatus.BREACHED, "svc-d");
        assertThat(result).isEmpty();
    }
}
