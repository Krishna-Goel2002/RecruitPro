package com.recruitpro.hr.controller;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.hr.dto.ApplicantDetailResponse;
import com.recruitpro.hr.dto.ApplicantSummaryResponse;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.ApplicationDecisionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * HR applicant list and detail (plan 5.4). Both GETs are read-only: they never call the AI service and never
 * write (H6, H8). Ranking spans every applicant of the job (C2).
 */
@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/applicants")
public class ApplicantController {
    private final ApplicantQueryService queryService;
    private final ApplicationDecisionService decisionService;

    public ApplicantController(ApplicantQueryService queryService, ApplicationDecisionService decisionService) {
        this.queryService = queryService;
        this.decisionService = decisionService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<PageResponse<ApplicantSummaryResponse>> list(@PathVariable Long jobId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size,
                                                                    @RequestParam(required = false) ApplicationStatus status,
                                                                    @RequestParam(defaultValue = "rank") String sort) {
        return ApiResponse.success(queryService.list(jobId, page, size, status, sort));
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<ApplicantDetailResponse> detail(@PathVariable Long jobId, @PathVariable Long applicationId) {
        return ApiResponse.success(queryService.detail(jobId, applicationId));
    }

    @PatchMapping("/{applicationId}/analysis/retry")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> retry(@PathVariable Long jobId, @PathVariable Long applicationId) {
        decisionService.retryAnalysis(jobId, applicationId);
        return ApiResponse.success("Resume analysis queued for retry");
    }
}
