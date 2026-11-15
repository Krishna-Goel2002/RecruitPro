package com.recruitpro.interview.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScorecardRequest;
import com.recruitpro.interview.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alias of {@code PUT /api/v1/interviews/{id}/scorecard} with the scorecard id in the path (the shape named in the
 * WP-PLAT brief). Same rules; a scorecard id that is not the interview's scorecard is 404.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','INTERVIEWER')")
@Tag(name = "Interviews")
public class ScorecardEditAliasController {

    private final InterviewService service;

    public ScorecardEditAliasController(InterviewService service) {
        this.service = service;
    }

    @PutMapping("/api/v1/hr/interviews/{interviewId}/scorecards/{scorecardId}")
    @Operation(summary = "Edit a scorecard (alias of PUT /api/v1/interviews/{id}/scorecard)")
    public ApiResponse<InterviewResponse> update(@PathVariable Long interviewId, @PathVariable Long scorecardId,
                                                 @Valid @RequestBody ScorecardRequest request) {
        return ApiResponse.success(service.updateScorecard(interviewId, scorecardId, request), "Scorecard updated");
    }
}
