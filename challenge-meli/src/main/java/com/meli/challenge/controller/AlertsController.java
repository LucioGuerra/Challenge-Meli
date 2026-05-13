package com.meli.challenge.controller;

import com.meli.challenge.dto.AlertResponse;
import com.meli.challenge.dto.ApiResponse;
import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;
import com.meli.challenge.service.AlertsService;
import com.meli.challenge.util.Paginator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@Validated
public class AlertsController {

    private static final String SERVICE_ID_PATTERN = "^[a-zA-Z0-9-]{1,64}$";

    private final AlertsService alertsService;

    public AlertsController(AlertsService alertsService) {
        this.alertsService = alertsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertResponse>>> list(
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) @Pattern(regexp = SERVICE_ID_PATTERN) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "${api.pagination.default-size}") @Min(1) @Max(100) int size) {

        List<Alert> filtered = alertsService.findAll(severity, status, serviceId);
        List<AlertResponse> data = Paginator.paginate(filtered, page, size).stream()
            .map(AlertResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.paginated(data, filtered.size(), page, size));
    }
}
