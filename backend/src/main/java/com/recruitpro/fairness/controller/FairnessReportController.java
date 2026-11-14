package com.recruitpro.fairness.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.fairness.dto.FairnessReport;
import com.recruitpro.fairness.service.FairnessReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Adverse-impact reports (plan 5.7): aggregates only, groups smaller than 5 suppressed. */
@RestController
@Tag(name = "Fairness")
public class FairnessReportController {

    private final FairnessReportService service;

    public FairnessReportController(FairnessReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/hr/jobs/{jobId}/fairness")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Fairness report of one job")
    public ApiResponse<FairnessReport> job(@PathVariable Long jobId) {
        return ApiResponse.success(service.forJob(jobId));
    }

    @GetMapping("/api/v1/admin/fairness")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fairness report of the whole organisation")
    public ApiResponse<FairnessReport> organization() {
        return ApiResponse.success(service.forOrganization());
    }
}
