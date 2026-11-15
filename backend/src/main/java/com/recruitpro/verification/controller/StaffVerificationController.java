package com.recruitpro.verification.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.verification.dto.StaffVerdict;
import com.recruitpro.verification.dto.StaffVerificationResponse;
import com.recruitpro.verification.dto.VerdictReviewRequest;
import com.recruitpro.verification.service.StaffVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff verification view of an application and per-organisation verdict reviews (plan v2 section 5). */
@RestController
@RequestMapping("/api/v1/hr")
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
public class StaffVerificationController {

    private final StaffVerificationService service;

    public StaffVerificationController(StaffVerificationService service) {
        this.service = service;
    }

    @GetMapping("/applications/{applicationId}/verification")
    @Operation(summary = "Shared verdicts of the applicant (blind-first; empty in SHADOW mode)")
    public ApiResponse<StaffVerificationResponse> view(@PathVariable Long applicationId) {
        return ApiResponse.success(service.view(applicationId));
    }

    @PostMapping("/verdicts/{verdictId}/review")
    @Operation(summary = "Confirm or overrule a shared verdict for your organisation (VISIBLE mode)")
    public ApiResponse<StaffVerdict> review(@PathVariable Long verdictId, @RequestBody VerdictReviewRequest request) {
        return ApiResponse.success(service.review(verdictId, request), "Review recorded");
    }
}
