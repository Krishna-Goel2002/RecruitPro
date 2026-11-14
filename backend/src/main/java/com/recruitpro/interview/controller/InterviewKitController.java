package com.recruitpro.interview.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.interview.dto.InterviewKitResponse;
import com.recruitpro.interview.dto.InterviewKitUpdateRequest;
import com.recruitpro.interview.service.InterviewKitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The interview kit of a job (plan 5.6). GET answers 404 while the job has no kit. */
@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/interview-kit")
@Tag(name = "Interviews")
public class InterviewKitController {

    private final InterviewKitService service;

    public InterviewKitController(InterviewKitService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<InterviewKitResponse> get(@PathVariable Long jobId) {
        return ApiResponse.success(service.get(jobId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<InterviewKitResponse> update(@PathVariable Long jobId, @Valid @RequestBody InterviewKitUpdateRequest request) {
        return ApiResponse.success(service.update(jobId, request), "Interview kit saved");
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Draft (or redraft) the job's interview kit with the AI service; replaces the current questions")
    public ApiResponse<InterviewKitResponse> generate(@PathVariable Long jobId) {
        return ApiResponse.success(service.generate(jobId), "Interview kit drafted");
    }
}
