package com.recruitpro.interview.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.interview.dto.CandidateInterviewResponse;
import com.recruitpro.interview.service.CandidateInterviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The candidate's interviews (plan 5.5). */
@RestController
@RequestMapping("/api/v1/candidates/me/interviews")
@PreAuthorize("hasRole('CANDIDATE')")
@Tag(name = "Candidate Portal")
public class CandidateInterviewController {

    private final CandidateInterviewService service;

    public CandidateInterviewController(CandidateInterviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CandidateInterviewResponse>> list() {
        return ApiResponse.success(service.listMine());
    }
}
