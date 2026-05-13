package com.meli.challenge.service;

import com.meli.challenge.client.AlertingClient;
import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;
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
class AlertsServiceTest {

    @Mock
    private AlertingClient alertingClient;

    @InjectMocks
    private AlertsService service;

    private final Alert criticalOld = new Alert("a-1", "svc-a", AlertSeverity.CRITICAL,
        "ServiceDown", "down", AlertStatus.FIRING,
        Instant.parse("2026-04-25T18:00:00Z"), null);
    private final Alert criticalNew = new Alert("a-2", "svc-b", AlertSeverity.CRITICAL,
        "HighErrorRate", "errors", AlertStatus.FIRING,
        Instant.parse("2026-05-06T13:45:00Z"), null);
    private final Alert mediumAck = new Alert("a-3", "svc-c", AlertSeverity.MEDIUM,
        "HighLatency", "p99 high", AlertStatus.ACKNOWLEDGED,
        Instant.parse("2026-05-06T12:30:00Z"), "alice@meli.com");
    private final Alert lowResolved = new Alert("a-4", "svc-a", AlertSeverity.LOW,
        "Elevated4xx", "minor", AlertStatus.RESOLVED,
        Instant.parse("2026-05-05T08:00:00Z"), "bob@meli.com");

    @BeforeEach
    void setupClient() {
        when(alertingClient.findAll()).thenReturn(List.of(lowResolved, mediumAck, criticalOld, criticalNew));
    }

    @Test
    void findAll_noFilters_sortsBySeverityThenStartedAtDesc() {
        List<Alert> result = service.findAll(null, null, null);
        assertThat(result).extracting(Alert::id)
            .containsExactly("a-2", "a-1", "a-3", "a-4");
    }

    @Test
    void findAll_filterBySeverity_returnsOnlyMatching() {
        List<Alert> result = service.findAll(AlertSeverity.CRITICAL, null, null);
        assertThat(result).extracting(Alert::id).containsExactly("a-2", "a-1");
    }

    @Test
    void findAll_filterByStatus_returnsOnlyMatching() {
        List<Alert> result = service.findAll(null, AlertStatus.RESOLVED, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("a-4");
    }

    @Test
    void findAll_filterByServiceId_returnsAllForThatService() {
        List<Alert> result = service.findAll(null, null, "svc-a");
        assertThat(result).extracting(Alert::id).containsExactly("a-1", "a-4");
    }

    @Test
    void findAll_allThreeFilters_returnsIntersection() {
        List<Alert> result = service.findAll(AlertSeverity.CRITICAL, AlertStatus.FIRING, "svc-b");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("a-2");
    }
}
