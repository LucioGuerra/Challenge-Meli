package com.meli.challenge.controller;

import com.meli.challenge.dto.ApiResponse;
import com.meli.challenge.dto.ServiceDetailResponse;
import com.meli.challenge.dto.ServiceMetricsResponse;
import com.meli.challenge.dto.ServiceResponse;
import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;
import com.meli.challenge.service.MetricsService;
import com.meli.challenge.service.ServiceCatalog;
import com.meli.challenge.util.Paginator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
@Validated
public class ServicesController {

    private static final String SERVICE_ID_PATTERN = "^[a-zA-Z0-9-]{1,64}$";
    private static final String SERVICE_ID_MESSAGE = "must be alphanumeric with hyphens, length 1-64";

    private final ServiceCatalog serviceCatalog;
    private final MetricsService metricsService;

    public ServicesController(ServiceCatalog serviceCatalog, MetricsService metricsService) {
        this.serviceCatalog = serviceCatalog;
        this.metricsService = metricsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ServiceResponse>>> list(
            @RequestParam(required = false) ServiceStatus status,
            @RequestParam(required = false) @Size(max = 100) String name,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "${api.pagination.default-size}") @Min(1) @Max(100) int size) {

        List<Service> filtered = serviceCatalog.findAll(status, name);
        List<ServiceResponse> data = Paginator.paginate(filtered, page, size).stream()
            .map(ServiceResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.paginated(data, filtered.size(), page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> get(
            @PathVariable
            @NotBlank
            @Pattern(regexp = SERVICE_ID_PATTERN, message = SERVICE_ID_MESSAGE)
            String id) {
        ServiceDetailResponse data = ServiceDetailResponse.from(serviceCatalog.findById(id));
        return ResponseEntity.ok(ApiResponse.single(data));
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<ApiResponse<ServiceMetricsResponse>> getMetrics(
            @PathVariable
            @NotBlank
            @Pattern(regexp = SERVICE_ID_PATTERN, message = SERVICE_ID_MESSAGE)
            String id) {
        ServiceMetricsResponse data = ServiceMetricsResponse.from(metricsService.getByServiceId(id));
        return ResponseEntity.ok(ApiResponse.withSource(data, "prometheus"));
    }
}
