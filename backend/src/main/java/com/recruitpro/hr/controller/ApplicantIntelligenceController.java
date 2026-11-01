package com.recruitpro.hr.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.hr.dto.ApplicantIntelligenceResponse;
import com.recruitpro.hr.service.ApplicantIntelligenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/applicants")
@RequiredArgsConstructor
public class ApplicantIntelligenceController {
    private final ApplicantIntelligenceService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<PageResponse<ApplicantIntelligenceResponse>> list(@PathVariable Long jobId,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @RequestParam(required = false) ApplicationStatus status) {
        return ApiResponse.success(service.list(jobId, page, size, status));
    }

    @PatchMapping("/{applicationId}/analysis/retry")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> retry(@PathVariable Long jobId, @PathVariable Long applicationId) {
        service.retry(jobId, applicationId);
        return ApiResponse.success("Resume analysis queued for retry");
    }
}
