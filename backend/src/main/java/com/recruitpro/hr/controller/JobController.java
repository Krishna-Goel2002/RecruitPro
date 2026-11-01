package com.recruitpro.hr.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {
    private final JobService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobResponse> create(@Valid @RequestBody JobCreateRequest request) {
        return ApiResponse.success(service.create(request), "Job created as draft");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','CANDIDATE')")
    public ApiResponse<PageResponse<JobResponse>> list(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) JobStatus status, @RequestParam(required=false) String department,
            @RequestParam(required=false) String location, @RequestParam(required=false) JobType jobType,
            @RequestParam(required=false) String search, @RequestParam(defaultValue="createdAt") String sort,
            @RequestParam(defaultValue="DESC") Sort.Direction direction, Authentication authentication) {
        boolean candidate = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE"));
        return ApiResponse.success(service.list(page, size, status, department, location, jobType, search, sort, direction, candidate));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','CANDIDATE')")
    public ApiResponse<JobResponse> get(@PathVariable Long id, Authentication authentication) {
        boolean candidate = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE"));
        return ApiResponse.success(service.get(id, candidate));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobResponse> update(@PathVariable Long id, @Valid @RequestBody JobUpdateRequest request) {
        return ApiResponse.success(service.update(id, request), "Job updated");
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobResponse> transition(@PathVariable Long id, @Valid @RequestBody JobStatusUpdateRequest request) {
        return ApiResponse.success(service.transition(id, request.status()), "Job status updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable Long id) { service.delete(id); return ApiResponse.success("Draft job deleted"); }

    @PostMapping("/{id}/requirements")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobRequirementResponse> addRequirement(@PathVariable Long id, @Valid @RequestBody JobRequirementCreateRequest request) {
        return ApiResponse.success(service.addRequirement(id, request), "Requirement added");
    }

    @PutMapping("/{id}/requirements/{requirementId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobRequirementResponse> updateRequirement(@PathVariable Long id, @PathVariable Long requirementId,
                                                                   @Valid @RequestBody JobRequirementUpdateRequest request) {
        return ApiResponse.success(service.updateRequirement(id, requirementId, request), "Requirement updated");
    }

    @DeleteMapping("/{id}/requirements/{requirementId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> deleteRequirement(@PathVariable Long id, @PathVariable Long requirementId) {
        service.deleteRequirement(id, requirementId); return ApiResponse.success("Requirement deleted");
    }
}
