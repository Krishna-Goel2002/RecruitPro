package com.recruitpro.verification.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.verification.dto.LabelView;
import com.recruitpro.verification.dto.ShadowQueueItem;
import com.recruitpro.verification.dto.VerdictLabelRequest;
import com.recruitpro.verification.dto.VerificationMetricsResponse;
import com.recruitpro.verification.dto.VerificationSettingsRequest;
import com.recruitpro.verification.dto.VerificationSettingsResponse;
import com.recruitpro.verification.service.ShadowEvaluationService;
import com.recruitpro.verification.service.VerificationSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Organisation verification settings and shadow evaluation (plan v2 section 5). */
@RestController
@RequestMapping("/api/v1/admin/verification")
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER')")
public class AdminVerificationController {

    private final VerificationSettingsService settingsService;
    private final ShadowEvaluationService evaluationService;

    public AdminVerificationController(VerificationSettingsService settingsService, ShadowEvaluationService evaluationService) {
        this.settingsService = settingsService;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Verification mode and connectors of the organisation")
    public ApiResponse<VerificationSettingsResponse> settings() {
        return ApiResponse.success(settingsService.getForCurrentOrganization());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change the verification mode (SHADOW or VISIBLE) and connectors (audited)")
    public ApiResponse<VerificationSettingsResponse> updateSettings(@RequestBody VerificationSettingsRequest request) {
        return ApiResponse.success(settingsService.update(request), "Verification settings saved");
    }

    @GetMapping("/shadow-queue")
    @Operation(summary = "Shared verdicts to label, unlabelled by you first")
    public ApiResponse<PageResponse<ShadowQueueItem>> shadowQueue(@RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(evaluationService.queue(page, size));
    }

    @PostMapping("/verdicts/{verdictId}/label")
    @Operation(summary = "Label a verdict with the verdict you believe is right")
    public ApiResponse<LabelView> label(@PathVariable Long verdictId, @RequestBody VerdictLabelRequest request) {
        return ApiResponse.success(evaluationService.label(verdictId, request), "Label saved");
    }

    @GetMapping("/metrics")
    @Operation(summary = "Agreement, false-corroboration rate, Cohen's kappa and readiness for VISIBLE mode")
    public ApiResponse<VerificationMetricsResponse> metrics() {
        return ApiResponse.success(evaluationService.metrics());
    }
}
