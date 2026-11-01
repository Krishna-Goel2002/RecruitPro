package com.recruitpro.candidate.service;

import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisFactResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisResponse;
import com.recruitpro.candidate.dto.ResumeFactReviewRequest;
import com.recruitpro.candidate.entity.*;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class ResumeIntelligenceService {
    private final CandidateProfileService profileService;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;

    public ResumeAnalysis createFor(Resume resume) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResume(resume);
        if ("pdf".equalsIgnoreCase(resume.getFileExtension()) || "docx".equalsIgnoreCase(resume.getFileExtension())) {
            analysis.setStatus(ResumeAnalysisStatus.PENDING);
        } else {
            analysis.setStatus(ResumeAnalysisStatus.UNSUPPORTED_FORMAT);
            analysis.setErrorCode("UNSUPPORTED_FORMAT");
        }
        return analysisRepository.save(analysis);
    }

    @Transactional(readOnly = true)
    public ResumeAnalysisResponse getMine(Long resumeId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        ResumeAnalysis analysis = analysisRepository.findByResumeIdAndResumeCandidateId(resumeId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        return response(analysis);
    }

    public ResumeAnalysisFactResponse reviewMine(Long resumeId, Long factId, ResumeFactReviewRequest request) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        ResumeAnalysis analysis = analysisRepository.findByResumeIdAndResumeCandidateId(resumeId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        if (analysis.getStatus() != ResumeAnalysisStatus.COMPLETED && analysis.getStatus() != ResumeAnalysisStatus.PARTIAL) {
            throw new BadRequestException("Resume information is not ready for review");
        }
        ResumeAnalysisFact fact = factRepository.findByIdAndAnalysisId(factId, analysis.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume fact not found"));
        String value = request.value().trim();
        fact.setReviewedValue(value.equals(fact.getExtractedValue()) ? null : value);
        fact.setReviewStatus(value.equals(fact.getExtractedValue()) ? ResumeFactReviewStatus.CONFIRMED : ResumeFactReviewStatus.CORRECTED);
        fact.setReviewer(candidate.getUser());
        fact.setReviewedAt(LocalDateTime.now());
        return ResumeAnalysisFactResponse.from(factRepository.save(fact));
    }

    @Transactional
    public WorkItem claim(Long analysisId) {
        int claimed = analysisRepository.claimPending(analysisId, ResumeAnalysisStatus.PENDING,
                ResumeAnalysisStatus.PROCESSING, LocalDateTime.now());
        if (claimed == 0) return null;
        ResumeAnalysis analysis = analysisRepository.findWithResumeById(analysisId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        Resume resume = analysis.getResume();
        return new WorkItem(analysis.getId(), resume.getStorageKey(), resume.getOriginalFilename(), resume.getContentType());
    }

    public void complete(Long analysisId, String sha256, AiResumeParseResponse result) {
        ResumeAnalysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        if (analysis.getStatus() != ResumeAnalysisStatus.PROCESSING) return;
        ResumeAnalysisStatus status = parseStatus(result.status());
        factRepository.deleteAllByAnalysisId(analysisId);
        if (result.facts() != null) {
            for (AiResumeParseResponse.Fact extracted : result.facts()) {
                ResumeAnalysisFact fact = new ResumeAnalysisFact();
                fact.setAnalysis(analysis);
                fact.setType(ResumeFactType.valueOf(extracted.type()));
                fact.setExtractedValue(extracted.value());
                fact.setNormalizedValue(extracted.normalizedValue());
                fact.setEvidence(extracted.evidence());
                fact.setSourceSection(extracted.sourceSection());
                fact.setSourceReference(extracted.sourceReference());
                fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED);
                factRepository.save(fact);
            }
        }
        analysis.setStatus(status);
        analysis.setParserVersion(result.parserVersion());
        analysis.setSourceSha256(sha256);
        analysis.setErrorCode(safeErrorCode(result.errorCode()));
        analysis.setCompletedAt(LocalDateTime.now());
        analysisRepository.save(analysis);
    }

    public void fail(Long analysisId, String errorCode) {
        analysisRepository.findById(analysisId).ifPresent(analysis -> {
            if (analysis.getStatus() != ResumeAnalysisStatus.PROCESSING) return;
            analysis.setStatus(ResumeAnalysisStatus.FAILED);
            analysis.setErrorCode(safeErrorCode(errorCode));
            analysis.setCompletedAt(LocalDateTime.now());
        });
    }

    public void retryMine(Long resumeId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        ResumeAnalysis analysis = analysisRepository.findByResumeIdAndResumeCandidateId(resumeId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        queueRetry(analysis);
    }

    public void retryForHr(Long resumeId) {
        ResumeAnalysis analysis = analysisRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        queueRetry(analysis);
    }

    public Long nextPendingId() {
        return analysisRepository.findFirstByStatusOrderByCreatedAtAscIdAsc(ResumeAnalysisStatus.PENDING)
                .map(ResumeAnalysis::getId).orElse(null);
    }

    public void recoverStaleProcessing() {
        analysisRepository.recoverStaleProcessing(ResumeAnalysisStatus.PROCESSING, ResumeAnalysisStatus.PENDING,
                LocalDateTime.now().minusMinutes(5));
    }

    @Transactional(readOnly = true, noRollbackFor = ResourceNotFoundException.class)
    public ResumeAnalysis getByResume(Long resumeId) {
        return findByResume(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
    }

    @Transactional(readOnly = true)
    public Optional<ResumeAnalysis> findByResume(Long resumeId) {
        if (analysisRepository == null) {
            try {
                return Optional.ofNullable(getByResume(resumeId));
            } catch (ResourceNotFoundException ex) {
                return Optional.empty();
            }
        }
        return analysisRepository.findByResumeId(resumeId);
    }

    @Transactional(readOnly = true)
    public List<ResumeAnalysisFact> getFacts(Long analysisId) {
        return factRepository.findAllByAnalysisIdOrderById(analysisId);
    }

    private void queueRetry(ResumeAnalysis analysis) {
        if (analysis.getStatus() != ResumeAnalysisStatus.FAILED) {
            throw new BadRequestException("Only failed resume analysis can be retried");
        }
        analysis.setStatus(ResumeAnalysisStatus.PENDING);
        analysis.setErrorCode(null);
        analysis.setCompletedAt(null);
        analysisRepository.save(analysis);
    }

    private ResumeAnalysisResponse response(ResumeAnalysis analysis) {
        return ResumeAnalysisResponse.from(analysis, factRepository.findAllByAnalysisIdOrderById(analysis.getId()));
    }

    private ResumeAnalysisStatus parseStatus(String status) {
        try { return ResumeAnalysisStatus.valueOf(status.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { return ResumeAnalysisStatus.FAILED; }
    }

    private String safeErrorCode(String value) {
        if (value == null || value.isBlank()) return null;
        return value.matches("[A-Z0-9_]{1,60}") ? value : "ANALYSIS_FAILED";
    }

    public record WorkItem(Long analysisId, String storageKey, String filename, String contentType) { }
}
