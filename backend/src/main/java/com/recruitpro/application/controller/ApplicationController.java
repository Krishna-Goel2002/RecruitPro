package com.recruitpro.application.controller;

import com.recruitpro.application.dto.ApplicationCreateRequest;
import com.recruitpro.application.dto.ApplicationResponse;
import com.recruitpro.application.service.ApplicationService;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('CANDIDATE')")
public class ApplicationController {
    private final ApplicationService service;

    @PostMapping("/api/v1/jobs/{jobId}/applications")
    @Operation(summary = "Apply to a published job with one of your uploaded resumes")
    public ApiResponse<ApplicationResponse> apply(@PathVariable Long jobId, @Valid @RequestBody ApplicationCreateRequest request) {
        return ApiResponse.success(service.apply(jobId, request), "Application submitted");
    }

    @GetMapping("/api/v1/candidates/me/applications")
    public ApiResponse<PageResponse<ApplicationResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listMine(page, size));
    }

    @GetMapping("/api/v1/candidates/me/applications/{applicationId}")
    public ApiResponse<ApplicationResponse> get(@PathVariable Long applicationId) {
        return ApiResponse.success(service.getMine(applicationId));
    }

    @PatchMapping("/api/v1/candidates/me/applications/{applicationId}/withdraw")
    public ApiResponse<ApplicationResponse> withdraw(@PathVariable Long applicationId) {
        return ApiResponse.success(service.withdraw(applicationId), "Application withdrawn");
    }
}
