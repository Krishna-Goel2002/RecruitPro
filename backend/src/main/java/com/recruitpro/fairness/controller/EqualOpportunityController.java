package com.recruitpro.fairness.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.fairness.dto.EqualOpportunityRequest;
import com.recruitpro.fairness.dto.EqualOpportunityResponse;
import com.recruitpro.fairness.service.EqualOpportunityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Voluntary equal-opportunity survey of the signed-in candidate (D14). Never visible to reviewers. */
@RestController
@RequestMapping("/api/v1/candidates/me/equal-opportunity")
@PreAuthorize("hasRole('CANDIDATE')")
@Tag(name = "Candidate Portal")
public class EqualOpportunityController {

    private final EqualOpportunityService service;

    public EqualOpportunityController(EqualOpportunityService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Stored survey answers (404 when none are stored)")
    public ApiResponse<EqualOpportunityResponse> get() {
        return ApiResponse.success(service.get());
    }

    @PutMapping
    @Operation(summary = "Save survey answers; consent must be true")
    public ApiResponse<EqualOpportunityResponse> save(@RequestBody EqualOpportunityRequest request) {
        return ApiResponse.success(service.save(request), "Your answers were saved");
    }

    @DeleteMapping
    @Operation(summary = "Delete the stored survey answers")
    public ApiResponse<Void> delete() {
        service.delete();
        return ApiResponse.success("Your answers were deleted");
    }
}
