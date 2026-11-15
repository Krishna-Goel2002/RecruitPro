package com.recruitpro.verification.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.verification.dto.LedgerClaim;
import com.recruitpro.verification.dto.OAuthStartResponse;
import com.recruitpro.verification.dto.OwnershipCheckRequest;
import com.recruitpro.verification.dto.ReferenceCreateRequest;
import com.recruitpro.verification.dto.ReferenceResponse;
import com.recruitpro.verification.dto.RunResponse;
import com.recruitpro.verification.dto.SourceCreateRequest;
import com.recruitpro.verification.dto.SourceResponse;
import com.recruitpro.verification.dto.VerdictDetail;
import com.recruitpro.verification.dto.VerdictResponseRequest;
import com.recruitpro.verification.service.CandidateLedgerService;
import com.recruitpro.verification.service.GitHubOAuthBindingCookie;
import com.recruitpro.verification.service.GitHubOAuthService;
import com.recruitpro.verification.service.ReferenceService;
import com.recruitpro.verification.service.VerificationRunService;
import com.recruitpro.verification.service.VerificationSourceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** The candidate's verification sources, runs, Claim Ledger and references (plan v2 section 5). */
@RestController
@RequestMapping("/api/v1/candidates/me/verification")
@PreAuthorize("hasRole('CANDIDATE')")
public class CandidateVerificationController {

    private final VerificationSourceService sourceService;
    private final VerificationRunService runService;
    private final GitHubOAuthService gitHubOAuthService;
    private final CandidateLedgerService ledgerService;
    private final ReferenceService referenceService;
    private final GitHubOAuthBindingCookie oauthCookie;

    public CandidateVerificationController(VerificationSourceService sourceService, VerificationRunService runService,
                                           GitHubOAuthService gitHubOAuthService, CandidateLedgerService ledgerService,
                                           ReferenceService referenceService, GitHubOAuthBindingCookie oauthCookie) {
        this.sourceService = sourceService;
        this.runService = runService;
        this.gitHubOAuthService = gitHubOAuthService;
        this.ledgerService = ledgerService;
        this.referenceService = referenceService;
        this.oauthCookie = oauthCookie;
    }

    // ---- sources ----

    @GetMapping("/sources")
    @Operation(summary = "List connected verification sources")
    public ApiResponse<List<SourceResponse>> sources() {
        return ApiResponse.success(sourceService.listMine());
    }

    @PostMapping(value = "/sources", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Connect a GitHub, GitLab, website or Open Badge source (consent required)")
    public ApiResponse<SourceResponse> create(@RequestBody SourceCreateRequest request) {
        return ApiResponse.success(sourceService.create(request), "Source connected");
    }

    @PostMapping(value = "/sources/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a PDF, DOCX or PPTX document (max 15 MB, consent required)")
    public ApiResponse<SourceResponse> upload(@RequestPart("file") MultipartFile file,
                                              @RequestParam(value = "consent", required = false) Boolean consent) {
        return ApiResponse.success(sourceService.createDocument(file, consent), "Document uploaded");
    }

    @PostMapping("/sources/{sourceId}/ownership-check")
    @Operation(summary = "Check the ownership challenge (bio code, DNS TXT, well-known file or page code)")
    public ApiResponse<SourceResponse> checkOwnership(@PathVariable Long sourceId,
                                                      @RequestBody(required = false) OwnershipCheckRequest request) {
        return ApiResponse.success(sourceService.checkOwnership(sourceId, request));
    }

    @DeleteMapping("/sources/{sourceId}")
    @Operation(summary = "Revoke a source: token, files, snapshots and evidence are deleted, verdicts withdrawn")
    public ApiResponse<Void> revoke(@PathVariable Long sourceId) {
        sourceService.revoke(sourceId);
        return ApiResponse.success("Source revoked");
    }

    @PostMapping("/sources/{sourceId}/runs")
    @Operation(summary = "Start a verification run (409 while one is running, 429 after 10 in 24 hours)")
    public ApiResponse<RunResponse> startRun(@PathVariable Long sourceId) {
        return ApiResponse.success(runService.startMine(sourceId), "Verification started");
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "One verification run (for polling)")
    public ApiResponse<RunResponse> run(@PathVariable Long runId) {
        return ApiResponse.success(runService.getMine(runId));
    }

    @GetMapping("/oauth/github/start")
    @Operation(summary = "Start connecting GitHub (503 OAUTH_NOT_CONFIGURED when unavailable)")
    public ApiResponse<OAuthStartResponse> startGitHub(HttpServletResponse response) {
        GitHubOAuthService.StartResult started = gitHubOAuthService.start();
        // Binds the state to this browser: the public callback requires this httpOnly cookie.
        response.addHeader(HttpHeaders.SET_COOKIE,
                oauthCookie.issue(started.browserNonce(), GitHubOAuthService.STATE_TTL).toString());
        return ApiResponse.success(started.response());
    }

    // ---- ledger ----

    @GetMapping("/ledger")
    @Operation(summary = "The Claim Ledger: current claims with their current verdicts")
    public ApiResponse<List<LedgerClaim>> ledger() {
        return ApiResponse.success(ledgerService.ledger());
    }

    @GetMapping("/verdicts/{verdictId}")
    @Operation(summary = "Verdict detail with reasons, integrity notes, probe question and evidence")
    public ApiResponse<VerdictDetail> verdict(@PathVariable Long verdictId) {
        return ApiResponse.success(ledgerService.detail(verdictId));
    }

    @PostMapping("/verdicts/{verdictId}/response")
    @Operation(summary = "Share, hide or re-check a verdict")
    public ApiResponse<VerdictDetail> respond(@PathVariable Long verdictId, @RequestBody VerdictResponseRequest request) {
        return ApiResponse.success(ledgerService.respond(verdictId, request));
    }

    // ---- references ----

    @GetMapping("/references")
    @Operation(summary = "List reference requests")
    public ApiResponse<List<ReferenceResponse>> references() {
        return ApiResponse.success(referenceService.listMine());
    }

    @PostMapping("/references")
    @Operation(summary = "Ask a referee to confirm claims (max 5 open requests)")
    public ApiResponse<ReferenceResponse> createReference(@RequestBody ReferenceCreateRequest request) {
        return ApiResponse.success(referenceService.create(request), "Reference request sent");
    }

    @PostMapping("/references/{referenceId}/cancel")
    @Operation(summary = "Cancel a reference request that is still waiting")
    public ApiResponse<ReferenceResponse> cancelReference(@PathVariable Long referenceId) {
        return ApiResponse.success(referenceService.cancel(referenceId), "Reference request cancelled");
    }
}
