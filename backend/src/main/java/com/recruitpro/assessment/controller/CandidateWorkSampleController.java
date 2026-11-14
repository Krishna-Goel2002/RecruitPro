package com.recruitpro.assessment.controller;

import com.recruitpro.assessment.dto.CandidateWorkSampleResponse;
import com.recruitpro.assessment.dto.WorkSampleSubmitRequest;
import com.recruitpro.assessment.service.CandidateWorkSampleService;
import com.recruitpro.common.dto.ApiResponse;
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

/** The candidate's work samples (plan 5.5): list, start (instructions revealed, timer starts) and submit. */
@RestController
@RequestMapping("/api/v1/candidates/me/work-samples")
@PreAuthorize("hasRole('CANDIDATE')")
@Tag(name = "Candidate Portal")
public class CandidateWorkSampleController {

    private final CandidateWorkSampleService service;

    public CandidateWorkSampleController(CandidateWorkSampleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CandidateWorkSampleResponse>> list() {
        return ApiResponse.success(service.listMine());
    }

    @PostMapping("/{assignmentId}/start")
    public ApiResponse<CandidateWorkSampleResponse> start(@PathVariable Long assignmentId) {
        return ApiResponse.success(service.start(assignmentId), "Work sample started");
    }

    @PostMapping("/{assignmentId}/submit")
    public ApiResponse<CandidateWorkSampleResponse> submit(@PathVariable Long assignmentId,
                                                           @Valid @RequestBody WorkSampleSubmitRequest request) {
        return ApiResponse.success(service.submit(assignmentId, request), "Work sample submitted");
    }
}
