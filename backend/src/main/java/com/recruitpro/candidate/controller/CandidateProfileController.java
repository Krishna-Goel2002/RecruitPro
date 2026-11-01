package com.recruitpro.candidate.controller;

import com.recruitpro.candidate.dto.CandidateProfileResponse;
import com.recruitpro.candidate.dto.CandidateProfileUpdateRequest;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/me")
@RequiredArgsConstructor
@Tag(name = "Candidate Portal")
@PreAuthorize("hasRole('CANDIDATE')")
public class CandidateProfileController {
    private final CandidateProfileService service;

    @GetMapping({"", "/profile"})
    @Operation(summary = "Get the authenticated candidate's profile")
    public ApiResponse<CandidateProfileResponse> getMine() { return ApiResponse.success(service.getMine()); }

    @PutMapping({"", "/profile"})
    @Operation(summary = "Update the authenticated candidate's profile")
    public ApiResponse<CandidateProfileResponse> updateMine(@Valid @RequestBody CandidateProfileUpdateRequest request) {
        return ApiResponse.success(service.updateMine(request), "Candidate profile updated");
    }
}
