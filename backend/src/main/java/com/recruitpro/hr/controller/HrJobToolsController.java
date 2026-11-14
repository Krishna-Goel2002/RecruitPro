package com.recruitpro.hr.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.InterviewerResponse;
import com.recruitpro.hr.dto.RematchResponse;
import com.recruitpro.hr.service.ApplicationDecisionService;
import com.recruitpro.hr.service.JobAuditExportService;
import com.recruitpro.hr.service.StaffDirectoryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Job-level HR tools: re-matching every applicant, the interviewer directory (plan 5.4) and the job's decision audit
 * export (WP-BE-C).
 */
@RestController
@RequestMapping("/api/v1/hr")
public class HrJobToolsController {
    private final ApplicationDecisionService decisionService;
    private final StaffDirectoryService staffDirectoryService;
    private final JobAuditExportService auditExportService;

    public HrJobToolsController(ApplicationDecisionService decisionService, StaffDirectoryService staffDirectoryService,
                                JobAuditExportService auditExportService) {
        this.decisionService = decisionService;
        this.staffDirectoryService = staffDirectoryService;
        this.auditExportService = auditExportService;
    }

    @PostMapping("/jobs/{jobId}/rematch")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ApiResponse<RematchResponse> rematch(@PathVariable Long jobId) {
        return ApiResponse.success(decisionService.rematch(jobId), "Re-matching queued");
    }

    /** CSV of every decision on the job's applications with the match run it was made on; audited (AUDIT_EXPORTED). */
    @GetMapping("/jobs/{jobId}/audit-export")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
    public ResponseEntity<byte[]> auditExport(@PathVariable Long jobId) {
        byte[] csv = auditExportService.exportCsv(jobId).getBytes(StandardCharsets.UTF_8);
        String filename = "job-" + jobId + "-audit-" + UtcTime.now().toLocalDate() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv);
    }

    @GetMapping("/interviewers")
    @PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','INTERVIEWER')")
    public ApiResponse<List<InterviewerResponse>> interviewers() {
        return ApiResponse.success(staffDirectoryService.interviewers());
    }
}
