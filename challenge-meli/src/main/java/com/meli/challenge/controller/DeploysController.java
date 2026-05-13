package com.meli.challenge.controller;

import com.meli.challenge.dto.ApiResponse;
import com.meli.challenge.dto.DeployResponse;
import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;
import com.meli.challenge.service.DeploysService;
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
@RequestMapping("/api/v1/deploys")
@Validated
public class DeploysController {

    private static final String SERVICE_ID_PATTERN = "^[a-zA-Z0-9-]{1,64}$";

    private final DeploysService deploysService;

    public DeploysController(DeploysService deploysService) {
        this.deploysService = deploysService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeployResponse>>> list(
            @RequestParam(required = false) DeployStatus status,
            @RequestParam(required = false) @Pattern(regexp = SERVICE_ID_PATTERN) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "${api.pagination.default-size}") @Min(1) @Max(100) int size) {

        List<Deploy> filtered = deploysService.findAll(status, serviceId);
        List<DeployResponse> data = Paginator.paginate(filtered, page, size).stream()
            .map(DeployResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.paginated(data, filtered.size(), page, size));
    }
}
