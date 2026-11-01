package com.recruitpro.hr.controller;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.hr.dto.ApplicationCommentRequest;
import com.recruitpro.hr.dto.ApplicationDecisionHistoryResponse;
import com.recruitpro.hr.dto.ApplicationDecisionRequest;
import com.recruitpro.hr.service.ApplicationReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
public class ApplicationReviewController {
    private final ApplicationReviewService service;

    @PostMapping("/shortlist")
    public ApiResponse<ApplicationDecisionHistoryResponse> shortlist(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody(required = false) ApplicationDecisionRequest request) {
        return ApiResponse.success(service.decide(jobId, applicationId, ApplicationStatus.SHORTLISTED, request), "Application shortlisted");
    }

    @PostMapping("/reject")
    public ApiResponse<ApplicationDecisionHistoryResponse> reject(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody(required = false) ApplicationDecisionRequest request) {
        return ApiResponse.success(service.decide(jobId, applicationId, ApplicationStatus.REJECTED, request), "Application rejected");
    }

    @PutMapping("/review-comment")
    public ApiResponse<ApplicationDecisionHistoryResponse> updateComment(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody ApplicationCommentRequest request) {
        return ApiResponse.success(service.updateComment(jobId, applicationId, request), "Review comment updated");
    }

    @GetMapping("/history")
    public ApiResponse<List<ApplicationDecisionHistoryResponse>> history(@PathVariable Long jobId, @PathVariable Long applicationId) {
        return ApiResponse.success(service.history(jobId, applicationId));
    }
}
