package com.recruitpro.hr.controller;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.dto.ClaimVerificationRequest;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.ApplicationDecisionService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Decisions, comments, reveal, staff resume download and claim verification on one application (plan 5.4).
 * Class default: ADMIN and HR_MANAGER; comments, history, resume download and claim verification also allow
 * RECRUITER (method-level annotations take precedence).
 */
@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}")
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
public class ApplicationReviewController {
    private final ApplicationDecisionService decisionService;
    private final ApplicantQueryService queryService;

    public ApplicationReviewController(ApplicationDecisionService decisionService, ApplicantQueryService queryService) {
        this.decisionService = decisionService;
        this.queryService = queryService;
    }

    @PostMapping("/decisions")
    public ApiResponse<DecisionEventResponse> decide(@PathVariable Long jobId, @PathVariable Long applicationId,
                                                     @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(decisionService.decide(jobId, applicationId, request), "Decision recorded");
    }

    /** Legacy alias of POST …/decisions {action: SHORTLIST}. */
    @PostMapping("/shortlist")
    public ApiResponse<DecisionEventResponse> shortlist(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody(required = false) ApplicationDecisionRequest request) {
        return ApiResponse.success(decisionService.decide(jobId, applicationId,
                new DecisionRequest(ApplicationDecisionAction.SHORTLIST, request == null ? null : request.comment())),
                "Application shortlisted");
    }

    /** Legacy alias of POST …/decisions {action: REJECT}. */
    @PostMapping("/reject")
    public ApiResponse<DecisionEventResponse> reject(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody(required = false) ApplicationDecisionRequest request) {
        return ApiResponse.success(decisionService.decide(jobId, applicationId,
                new DecisionRequest(ApplicationDecisionAction.REJECT, request == null ? null : request.comment())),
                "Application rejected");
    }

    @PutMapping("/review-comment")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<DecisionEventResponse> updateComment(@PathVariable Long jobId, @PathVariable Long applicationId,
            @Valid @RequestBody ApplicationCommentRequest request) {
        return ApiResponse.success(decisionService.updateComment(jobId, applicationId, request), "Review comment updated");
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<List<DecisionEventResponse>> history(@PathVariable Long jobId, @PathVariable Long applicationId) {
        return ApiResponse.success(queryService.history(jobId, applicationId));
    }

    @PostMapping("/reveal")
    public ApiResponse<ApplicantDetailResponse> reveal(@PathVariable Long jobId, @PathVariable Long applicationId) {
        return ApiResponse.success(decisionService.reveal(jobId, applicationId), "Identity revealed");
    }

    @GetMapping("/resume")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ResponseEntity<ByteArrayResource> resume(@PathVariable Long jobId, @PathVariable Long applicationId) {
        ApplicationDecisionService.ResumeFile file = decisionService.downloadResume(jobId, applicationId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.contentType());
        } catch (RuntimeException ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ByteArrayResource(file.bytes()));
    }

    @PostMapping("/claims/{claimId}/verification")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<CandidateClaimResponse> verifyClaim(@PathVariable Long jobId, @PathVariable Long applicationId,
                                                           @PathVariable Long claimId,
                                                           @Valid @RequestBody ClaimVerificationRequest request) {
        return ApiResponse.success(decisionService.verifyClaim(jobId, applicationId, claimId, request), "Claim verification recorded");
    }
}
