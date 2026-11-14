package com.recruitpro.candidate.privacy;

import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.time.UtcTime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The candidate's privacy rights (plan 5.5, D14): data export and account deletion. */
@RestController
@RequestMapping("/api/v1/candidates/me")
@PreAuthorize("hasRole('CANDIDATE')")
@Tag(name = "Candidate Portal")
public class CandidatePrivacyController {

    private final CandidateDataExportService exportService;
    private final CandidateAccountDeletionService deletionService;
    private final RefreshTokenCookies refreshTokenCookies;

    public CandidatePrivacyController(CandidateDataExportService exportService,
                                      CandidateAccountDeletionService deletionService,
                                      RefreshTokenCookies refreshTokenCookies) {
        this.exportService = exportService;
        this.deletionService = deletionService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    /** JSON attachment (not wrapped in the API envelope) with every record about the candidate. */
    @GetMapping("/export")
    @Operation(summary = "Download every record RecruitPro keeps about you (JSON attachment)")
    public ResponseEntity<CandidateDataExport> export() {
        CandidateDataExport export = exportService.exportMine();
        String filename = "recruitpro-my-data-" + UtcTime.now().toLocalDate() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(export);
    }

    /** Anonymises the account after the password is confirmed (wrong password: 400) and clears the refresh cookie. */
    @DeleteMapping
    @Operation(summary = "Delete your account: anonymises it and deletes files, facts, claims and survey answers")
    public ApiResponse<Void> delete(@Valid @RequestBody DeleteAccountRequest request,
                                    HttpServletRequest httpRequest, HttpServletResponse response) {
        deletionService.deleteMyAccount(request.password());
        // The tokens are already revoked; clear the cookie of the portal the request came from (rp_refresh without one).
        refreshTokenCookies.clear(Portal.from(httpRequest), response);
        return ApiResponse.success("Your account was deleted");
    }
}
