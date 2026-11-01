package com.recruitpro.candidate.controller;

import com.recruitpro.candidate.dto.ResumeResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisFactResponse;
import com.recruitpro.candidate.dto.ResumeFactReviewRequest;
import com.recruitpro.candidate.service.ResumeService;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/v1/candidates/me/resumes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CANDIDATE')")
@Validated
public class ResumeController {
    private final ResumeService service;
    private final ResumeIntelligenceService intelligenceService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a resume for the authenticated candidate")
    public ApiResponse<ResumeResponse> upload(@RequestPart("file") @NotNull MultipartFile file) {
        return ApiResponse.success(service.upload(file), "Resume uploaded");
    }

    @GetMapping
    public ApiResponse<List<ResumeResponse>> list() { return ApiResponse.success(service.listMine()); }

    @GetMapping("/{resumeId}")
    public ApiResponse<ResumeResponse> get(@PathVariable Long resumeId) { return ApiResponse.success(service.getMine(resumeId)); }

    @GetMapping("/{resumeId}/download")
    @Operation(summary = "Download one of the authenticated candidate's resumes")
    public ResponseEntity<ByteArrayResource> download(@PathVariable Long resumeId) {
        ResumeService.Download download = service.getDownload(resumeId);
        String extension = download.resume().getFileExtension();
        String safeName = "resume-" + download.resume().getId() + "." + extension;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.resume().getContentType()))
                .contentLength(download.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(safeName).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(download.bytes()));
    }

    @GetMapping("/{resumeId}/analysis")
    public ApiResponse<ResumeAnalysisResponse> analysis(@PathVariable Long resumeId) {
        return ApiResponse.success(intelligenceService.getMine(resumeId));
    }

    @PatchMapping("/{resumeId}/analysis/retry")
    public ApiResponse<Void> retryAnalysis(@PathVariable Long resumeId) {
        intelligenceService.retryMine(resumeId);
        return ApiResponse.success("Resume analysis queued for retry");
    }

    @PatchMapping("/{resumeId}/analysis/facts/{factId}")
    public ApiResponse<ResumeAnalysisFactResponse> reviewFact(@PathVariable Long resumeId, @PathVariable Long factId,
                                                               @Valid @RequestBody ResumeFactReviewRequest request) {
        return ApiResponse.success(intelligenceService.reviewMine(resumeId, factId, request), "Resume information reviewed");
    }
}
