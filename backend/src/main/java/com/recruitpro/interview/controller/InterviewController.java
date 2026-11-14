package com.recruitpro.interview.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.interview.dto.InterviewDetailResponse;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScorecardRequest;
import com.recruitpro.interview.dto.UpdateInterviewRequest;
import com.recruitpro.interview.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Interviewer endpoints (plan 5.6). Every staff role (including INTERVIEWER) may call them; the service checks that
 * the caller is the assigned interviewer (or ADMIN/HR_MANAGER for viewing) and that the interview belongs to the
 * caller's organisation (otherwise 404).
 */
@RestController
@RequestMapping("/api/v1/interviews")
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','INTERVIEWER')")
@Tag(name = "Interviews")
public class InterviewController {

    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    @Operation(summary = "Interviews assigned to the caller, soonest first")
    public ApiResponse<List<InterviewResponse>> mine() {
        return ApiResponse.success(service.mine());
    }

    @GetMapping("/{interviewId}")
    @Operation(summary = "Interview with the job's kit and focus requirements (assigned interviewer, ADMIN, HR_MANAGER)")
    public ApiResponse<InterviewDetailResponse> detail(@PathVariable Long interviewId) {
        return ApiResponse.success(service.detail(interviewId));
    }

    @PatchMapping("/{interviewId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Reschedule or cancel a scheduled interview")
    public ApiResponse<InterviewResponse> update(@PathVariable Long interviewId, @Valid @RequestBody UpdateInterviewRequest request) {
        return ApiResponse.success(service.update(interviewId, request), "Interview updated");
    }

    @PostMapping("/{interviewId}/scorecard")
    @Operation(summary = "Submit the scorecard (assigned interviewer only); completes the interview and queues re-matching")
    public ApiResponse<InterviewResponse> submitScorecard(@PathVariable Long interviewId,
                                                          @Valid @RequestBody ScorecardRequest request) {
        return ApiResponse.success(service.submitScorecard(interviewId, request), "Scorecard submitted");
    }
}
