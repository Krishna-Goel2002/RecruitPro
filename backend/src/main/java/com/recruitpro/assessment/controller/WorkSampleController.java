package com.recruitpro.assessment.controller;

import com.recruitpro.assessment.dto.AssignWorkSampleRequest;
import com.recruitpro.assessment.dto.CancelAssignmentRequest;
import com.recruitpro.assessment.dto.ScoreRequest;
import com.recruitpro.assessment.dto.WorkSampleAssignmentResponse;
import com.recruitpro.assessment.dto.WorkSampleResponse;
import com.recruitpro.assessment.dto.WorkSampleStatusRequest;
import com.recruitpro.assessment.dto.WorkSampleUpdateRequest;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.service.WorkSampleService;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Work samples, staff side (plan 5.6). Tenancy: every path is reached through a job of the caller's organisation;
 * another organisation's job, sample or assignment is 404.
 */
@RestController
@RequestMapping("/api/v1/hr")
@Tag(name = "Work samples")
public class WorkSampleController {

    private final WorkSampleService service;

    public WorkSampleController(WorkSampleService service) {
        this.service = service;
    }

    @PostMapping("/jobs/{jobId}/work-samples/generate")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Draft a work sample with an anchored rubric from the job's requirements (stored as DRAFT)")
    public ApiResponse<WorkSampleResponse> generate(@PathVariable Long jobId) {
        return ApiResponse.success(service.generate(jobId), "Work sample drafted");
    }

    @GetMapping("/jobs/{jobId}/work-samples")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<List<WorkSampleResponse>> list(@PathVariable Long jobId) {
        return ApiResponse.success(service.list(jobId));
    }

    @PutMapping("/jobs/{jobId}/work-samples/{workSampleId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<WorkSampleResponse> update(@PathVariable Long jobId, @PathVariable Long workSampleId,
                                                  @Valid @RequestBody WorkSampleUpdateRequest request) {
        return ApiResponse.success(service.update(jobId, workSampleId, request), "Work sample updated");
    }

    @PatchMapping("/jobs/{jobId}/work-samples/{workSampleId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<WorkSampleResponse> changeStatus(@PathVariable Long jobId, @PathVariable Long workSampleId,
                                                        @Valid @RequestBody WorkSampleStatusRequest request) {
        return ApiResponse.success(service.changeStatus(jobId, workSampleId, request), "Work sample status updated");
    }

    @PostMapping("/jobs/{jobId}/applicants/{applicationId}/work-samples")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Assign an ACTIVE work sample to a SHORTLISTED or INTERVIEWING application")
    public ApiResponse<WorkSampleAssignmentResponse> assign(@PathVariable Long jobId, @PathVariable Long applicationId,
                                                            @Valid @RequestBody AssignWorkSampleRequest request) {
        return ApiResponse.success(service.assign(jobId, applicationId, request), "Work sample assigned");
    }

    @GetMapping("/jobs/{jobId}/work-sample-assignments")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    public ApiResponse<List<WorkSampleAssignmentResponse>> assignments(@PathVariable Long jobId,
            @RequestParam(required = false) WorkSampleAssignmentStatus status) {
        return ApiResponse.success(service.listAssignments(jobId, status));
    }

    @PostMapping("/work-sample-assignments/{assignmentId}/score")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    @Operation(summary = "Score a submitted work sample, 1-4 per rubric criterion (queues re-matching)")
    public ApiResponse<WorkSampleAssignmentResponse> score(@PathVariable Long assignmentId,
                                                           @Valid @RequestBody ScoreRequest request) {
        return ApiResponse.success(service.score(assignmentId, request), "Work sample scored");
    }

    @PostMapping("/work-sample-assignments/{assignmentId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    @Operation(summary = "Cancel an assignment with a reason (candidate notified; no longer counts as evidence)")
    public ApiResponse<WorkSampleAssignmentResponse> cancel(@PathVariable Long assignmentId,
                                                            @Valid @RequestBody CancelAssignmentRequest request) {
        return ApiResponse.success(service.cancel(assignmentId, request), "Work sample assignment cancelled");
    }

    @GetMapping("/work-sample-assignments/{assignmentId}/files/{fileId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER')")
    @Operation(summary = "Download a file the candidate submitted (audited)")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long assignmentId, @PathVariable Long fileId) {
        return WorkSampleFileResponses.of(service.downloadFile(assignmentId, fileId));
    }
}
