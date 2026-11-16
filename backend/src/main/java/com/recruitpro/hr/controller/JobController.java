package com.recruitpro.hr.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.service.JobService;
import com.recruitpro.hr.service.RequirementCompilerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Jobs and requirements (plan 5.3). Staff get {@link JobStaffResponse} for their organisation; candidates get
 * {@link JobPublicResponse} for open jobs of every organisation.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {
    private final JobService service;
    private final RequirementCompilerService compilerService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobStaffResponse> create(@Valid @RequestBody JobCreateRequest request) {
        return ApiResponse.success(service.create(request), "Job created as draft");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','CANDIDATE')")
    public ApiResponse<?> list(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) JobStatus status, @RequestParam(required=false) String department,
            @RequestParam(required=false) String location, @RequestParam(required=false) JobType jobType,
            @RequestParam(required=false) String search, @RequestParam(defaultValue="createdAt") String sort,
            @RequestParam(defaultValue="DESC") Sort.Direction direction, Authentication authentication) {
        if (isCandidate(authentication)) {
            return ApiResponse.success(service.listForCandidate(page, size, department, location, jobType, search, sort, direction));
        }
        return ApiResponse.success(service.listForStaff(page, size, status, department, location, jobType, search, sort, direction));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','CANDIDATE')")
    public ApiResponse<?> get(@PathVariable Long id, Authentication authentication) {
        if (isCandidate(authentication)) {
            return ApiResponse.success(service.getForCandidate(id));
        }
        return ApiResponse.success(service.getForStaff(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobStaffResponse> update(@PathVariable Long id, @Valid @RequestBody JobUpdateRequest request) {
        return ApiResponse.success(service.update(id, request), "Job updated");
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobStaffResponse> transition(@PathVariable Long id, @Valid @RequestBody JobStatusUpdateRequest request) {
        return ApiResponse.success(service.transition(id, request.status()), "Job status updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable Long id) { service.delete(id); return ApiResponse.success("Draft job deleted"); }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobStaffResponse> duplicate(@PathVariable Long id) {
        return ApiResponse.success(service.duplicate(id), "Job copied as a draft");
    }

    @PostMapping("/compile-requirements")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<CompileRequirementsResponse> compileRequirements(@Valid @RequestBody CompileRequirementsRequest request) {
        return ApiResponse.success(compilerService.compile(request), "Requirement suggestions drafted");
    }

    @PostMapping("/{id}/requirements")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<RequirementResponse> addRequirement(@PathVariable Long id, @Valid @RequestBody RequirementInput request) {
        return ApiResponse.success(service.addRequirement(id, request), "Requirement added");
    }

    @PutMapping("/{id}/requirements")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<JobStaffResponse> replaceRequirements(@PathVariable Long id, @Valid @RequestBody RequirementsReplaceRequest request) {
        return ApiResponse.success(service.replaceRequirements(id, request), "Requirements saved");
    }

    @PutMapping("/{id}/requirements/{requirementId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<RequirementResponse> updateRequirement(@PathVariable Long id, @PathVariable Long requirementId,
                                                              @Valid @RequestBody RequirementInput request) {
        return ApiResponse.success(service.updateRequirement(id, requirementId, request), "Requirement updated");
    }

    @DeleteMapping("/{id}/requirements/{requirementId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<Void> deleteRequirement(@PathVariable Long id, @PathVariable Long requirementId) {
        service.deleteRequirement(id, requirementId); return ApiResponse.success("Requirement deleted");
    }

    private static boolean isCandidate(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE"));
    }
}
