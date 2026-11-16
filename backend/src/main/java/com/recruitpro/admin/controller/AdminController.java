package com.recruitpro.admin.controller;

import com.recruitpro.admin.dto.*;
import com.recruitpro.admin.service.AdminDashboardService;
import com.recruitpro.admin.service.AdminHrManagementService;
import com.recruitpro.admin.service.AdminJobService;
import com.recruitpro.admin.service.AdminUserService;
import com.recruitpro.audit.dto.AuditEventResponse;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.user.entity.Role;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Organisation administration (ADMIN of the caller's organisation; every query is organisation-scoped). */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminDashboardService dashboardService;
    private final AdminUserService userService;
    private final AdminHrManagementService hrService;
    private final AdminJobService jobService;
    private final AuditService auditService;

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

    /** M10: persistent audit trail of the organisation, newest first, optionally filtered by action. */
    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<AuditEventResponse>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action
    ) {
        return ApiResponse.success(auditService.listForCurrentOrganization(page, size, action));
    }

    /** Response header of the audit CSV export: {@code true} when the row cap cut the export off (exposed by CORS). */
    public static final String AUDIT_EXPORT_TRUNCATED_HEADER = "X-Export-Truncated";

    /** CSV export of the organisation's audit trail ({@code from}/{@code to}: ISO-8601 dates or date-times). */
    @GetMapping("/audit-export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        AuditService.AuditExport export = auditService.exportForCurrentOrganization(from, to);
        byte[] csv = export.csv().getBytes(StandardCharsets.UTF_8);
        String filename = "audit-" + UtcTime.now().toLocalDate() + ".csv";
        // R6-B5: never cached (the trail names staff and IPs), like the job audit export; R1-U8: the portal warns when
        // the row cap cut the file off.
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(AUDIT_EXPORT_TRUNCATED_HEADER, String.valueOf(export.truncated()))
                .body(csv);
    }
}
