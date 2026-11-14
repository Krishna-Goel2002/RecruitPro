package com.recruitpro.interview.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScheduleInterviewRequest;
import com.recruitpro.interview.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Interviews of one applicant (plan 5.6). */
@RestController
@RequestMapping("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews")
@Tag(name = "Interviews")
public class ApplicantInterviewController {

    private final InterviewService service;

    public ApplicantInterviewController(InterviewService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Schedule an interview; the first one moves a SHORTLISTED application to INTERVIEWING")
    public ApiResponse<InterviewResponse> schedule(@PathVariable Long jobId, @PathVariable Long applicationId,
                                                   @Valid @RequestBody ScheduleInterviewRequest request) {
        return ApiResponse.success(service.schedule(jobId, applicationId, request), "Interview scheduled");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<List<InterviewResponse>> list(@PathVariable Long jobId, @PathVariable Long applicationId) {
        return ApiResponse.success(service.listForApplicant(jobId, applicationId));
    }
}
