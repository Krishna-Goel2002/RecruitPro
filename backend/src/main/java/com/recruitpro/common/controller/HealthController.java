package com.recruitpro.common.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.HealthStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public health check controller exposing only the application status.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Minimal public application health check")
public class HealthController {

    @GetMapping
    @Operation(summary = "Get Application Health Status", description = "Returns the public application status.")
    public ResponseEntity<ApiResponse<HealthStatusResponse>> getHealthStatus() {
        return ResponseEntity.ok(ApiResponse.success(
                new HealthStatusResponse("UP"), "Health check passed"));
    }
}
