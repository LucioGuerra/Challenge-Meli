package com.meli.challenge.controller;

import com.meli.challenge.dto.ApiResponse;
import com.meli.challenge.dto.SloResponse;
import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;
import com.meli.challenge.service.SlosService;
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
@RequestMapping("/api/v1/slos")
@Validated
public class SlosController {

    private static final String SERVICE_ID_PATTERN = "^[a-zA-Z0-9-]{1,64}$";

    private final SlosService slosService;

    public SlosController(SlosService slosService) {
        this.slosService = slosService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SloResponse>>> list(
            @RequestParam(required = false) SloStatus status,
            @RequestParam(required = false) @Pattern(regexp = SERVICE_ID_PATTERN) String serviceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "${api.pagination.default-size}") @Min(1) @Max(100) int size) {

        List<Slo> filtered = slosService.findAll(status, serviceId);
        List<SloResponse> data = Paginator.paginate(filtered, page, size).stream()
            .map(SloResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.paginated(data, filtered.size(), page, size));
    }
}
