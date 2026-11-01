package com.recruitpro.admin.controller;

import com.recruitpro.admin.audit.AdminAuditService;
import com.recruitpro.admin.dto.*;
import com.recruitpro.admin.service.AdminDashboardService;
import com.recruitpro.admin.service.AdminHrManagementService;
import com.recruitpro.admin.service.AdminJobService;
import com.recruitpro.admin.service.AdminUserService;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.user.entity.Role;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminDashboardService dashboardService;
    private final AdminUserService userService;
    private final AdminHrManagementService hrService;
    private final AdminJobService jobService;
    private final AdminAuditService auditService;

    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardMetricsResponse> getDashboardMetrics() {
        return ApiResponse.success(dashboardService.getDashboardMetrics());
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean enabled
    ) {
        return ApiResponse.success(userService.listUsers(page, size, search, role, enabled));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<AdminUserResponse> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserStatusUpdateRequest request
    ) {
        return ApiResponse.success(userService.updateUserStatus(userId, request.enabled()), "User status updated");
    }

    @PatchMapping("/users/{userId}/role")
    public ApiResponse<AdminUserResponse> updateUserRole(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserRoleUpdateRequest request
    ) {
        return ApiResponse.success(userService.updateUserRole(userId, request.role()), "User role updated");
    }

    @GetMapping("/hr-team")
    public ApiResponse<List<AdminHrMemberResponse>> listHrTeam() {
        return ApiResponse.success(hrService.listHrTeam());
    }

    @GetMapping("/jobs")
    public ApiResponse<PageResponse<AdminJobOverviewResponse>> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String search
    ) {
        return ApiResponse.success(jobService.listJobs(page, size, status, department, search));
    }

    @PatchMapping("/jobs/{jobId}/status")
    public ApiResponse<AdminJobOverviewResponse> overrideJobStatus(
            @PathVariable Long jobId,
            @Valid @RequestBody AdminJobStatusUpdateRequest request
    ) {
        return ApiResponse.success(jobService.overrideJobStatus(jobId, request.status(), request.reason()), "Job status updated by administrator");
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<AdminAuditRecord>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(auditService.list(page, size));
    }
}
