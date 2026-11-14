package com.recruitpro.evidence.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.evidence.dto.CandidateClaimCreateRequest;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.service.CandidateClaimService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Candidate claims (plan 5.5): unverified until a recruiter checks them (C1). */
@RestController
@RequestMapping("/api/v1/candidates/me/claims")
@PreAuthorize("hasRole('CANDIDATE')")
public class CandidateClaimController {

    private final CandidateClaimService service;

    public CandidateClaimController(CandidateClaimService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the authenticated candidate's claims")
    public ApiResponse<List<CandidateClaimResponse>> list() {
        return ApiResponse.success(service.listMine());
    }

    @PostMapping
    @Operation(summary = "Add a claim (self-claimed until a recruiter verifies it)")
    public ApiResponse<CandidateClaimResponse> create(@Valid @RequestBody CandidateClaimCreateRequest request) {
        return ApiResponse.success(service.createMine(request), "Claim added");
    }

    @DeleteMapping("/{claimId}")
    public ApiResponse<Void> delete(@PathVariable Long claimId) {
        service.deleteMine(claimId);
        return ApiResponse.success("Claim deleted");
    }
}
