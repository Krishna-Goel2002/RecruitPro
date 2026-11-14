package com.recruitpro.candidate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisFactResponse;
import com.recruitpro.candidate.dto.ResumeAnalysisResponse;
import com.recruitpro.candidate.dto.ResumeFactReviewRequest;
import com.recruitpro.candidate.entity.*;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.service.CandidateClaimService;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ResumeIntelligenceService {
    private static final Set<ResumeAnalysisStatus> CLAIMABLE = EnumSet.of(ResumeAnalysisStatus.PENDING, ResumeAnalysisStatus.PROCESSING);
    private static final Set<ResumeAnalysisStatus> TERMINAL_FROM_PARSER = EnumSet.of(ResumeAnalysisStatus.COMPLETED,
            ResumeAnalysisStatus.PARTIAL, ResumeAnalysisStatus.FAILED, ResumeAnalysisStatus.NEEDS_OCR,
            ResumeAnalysisStatus.UNSUPPORTED_FORMAT);
    private static final int MAX_ATTRIBUTES_JSON = 8_000;
    private static final int MAX_TIMELINE_JSON = 200_000;

    private final CandidateProfileService profileService;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;
    private final BackgroundJobService backgroundJobService;
    private final CandidateClaimService claimService;
    private final CandidateClaimRepository claimRepository;
    private final MatchTriggerService matchTriggerService;
    private final ObjectMapper objectMapper;

    /** Creates the analysis row for a new resume and queues a PARSE_RESUME job for parseable formats. */
    public ResumeAnalysis createFor(Resume resume) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResume(resume);
        if ("pdf".equalsIgnoreCase(resume.getFileExtension()) || "docx".equalsIgnoreCase(resume.getFileExtension())) {
            analysis.setStatus(ResumeAnalysisStatus.PENDING);
        } else {
            analysis.setStatus(ResumeAnalysisStatus.UNSUPPORTED_FORMAT);
            analysis.setErrorCode("UNSUPPORTED_FORMAT");
        }
        ResumeAnalysis saved = analysisRepository.save(analysis);
        if (saved.getStatus() == ResumeAnalysisStatus.PENDING) {
            backgroundJobService.enqueue(BackgroundJobType.PARSE_RESUME, saved.getId());
        }
        return saved;
    }

    /** Not read-only: {@code getOrProvisionMine()} may create a missing candidate profile. Reads only otherwise. */
    public ResumeAnalysisResponse getMine(Long resumeId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        ResumeAnalysis analysis = analysisRepository.findByResumeIdAndResumeCandidateId(resumeId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        List<ResumeAnalysisFact> facts = factRepository.findAllByAnalysisIdOrderById(analysis.getId());
        Map<Long, Long> claimIdsByFact = new HashMap<>();
        if (!facts.isEmpty()) {
            for (CandidateClaim claim : claimRepository.findAllByCandidateIdAndSourceFactIdIn(candidate.getId(),
                    facts.stream().map(ResumeAnalysisFact::getId).toList())) {
                claimIdsByFact.putIfAbsent(claim.getSourceFactId(), claim.getId());
            }
        }
        List<ResumeAnalysisFactResponse> factResponses = facts.stream()
                .map(fact -> factResponse(fact, claimIdsByFact.get(fact.getId()))).toList();
        JsonNode timeline = JsonText.read(objectMapper, analysis.getTimelineJson());
        return ResumeAnalysisResponse.from(analysis, factResponses, ResumeFlags.parse(objectMapper, analysis.getFlagsJson()),
                timeline != null && timeline.isObject() ? timeline : null);
    }

    /**
     * Candidate review of a fact (C1). The extracted value is never overwritten: a changed value is stored as
     * the reviewed value and becomes a SELF_CLAIMED claim linked to the fact (created or updated); confirming the
     * extracted value removes that claim. Contact facts (EMAIL, PHONE, LINK) never become claims.
     */
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
        boolean confirmed = value.equals(fact.getExtractedValue());
        fact.setReviewedValue(confirmed ? null : value);
        fact.setReviewStatus(confirmed ? ResumeFactReviewStatus.CONFIRMED : ResumeFactReviewStatus.CORRECTED);
        fact.setReviewer(candidate.getUser());
        fact.setReviewedAt(UtcTime.now());
        ResumeAnalysisFact saved = factRepository.save(fact);
        Long claimId = null;
        if (confirmed) {
            claimService.removeForFact(candidate, saved.getId());
        } else {
            CandidateClaim claim = claimService.upsertForFact(candidate, saved, value);
            claimId = claim == null ? null : claim.getId();
        }
        return factResponse(saved, claimId);
    }

    /**
     * Claims an analysis for the PARSE_RESUME job (PENDING or PROCESSING to PROCESSING). Returns null when
     * the analysis no longer waits for parsing.
     */
    public WorkItem claimForProcessing(Long analysisId) {
        int claimed = analysisRepository.claimForProcessing(analysisId, CLAIMABLE, ResumeAnalysisStatus.PROCESSING, UtcTime.now());
        if (claimed == 0) return null;
        ResumeAnalysis analysis = analysisRepository.findWithResumeById(analysisId).orElse(null);
        if (analysis == null) return null;
        Resume resume = analysis.getResume();
        return new WorkItem(analysis.getId(), resume.getStorageKey(), resume.getOriginalFilename(), resume.getContentType());
    }

    /**
     * Stores a parser outcome (COMPLETED, PARTIAL, NEEDS_OCR, FAILED, UNSUPPORTED_FORMAT): facts at tier
     * RESUME_STATED with spans and attributes (LINK facts included; they are identity data shown only to the
     * candidate), parser flags and the timeline. Queues matching for every application using the resume.
     */
    public void complete(Long analysisId, String sha256, AiResumeParseResponse result) {
        ResumeAnalysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume analysis not found"));
        if (analysis.getStatus() != ResumeAnalysisStatus.PROCESSING) return;
        ResumeAnalysisStatus status = parseStatus(result.status());
        boolean withFacts = status == ResumeAnalysisStatus.COMPLETED || status == ResumeAnalysisStatus.PARTIAL;
        factRepository.deleteAllByAnalysisId(analysisId);
        if (result.facts() != null && withFacts) {
            for (AiResumeParseResponse.Fact extracted : result.facts()) {
                ResumeFactType type = factType(extracted == null ? null : extracted.type());
                String value = limit(extracted == null ? null : extracted.value(), 1000);
                if (type == null || value == null || value.isBlank()) continue;
                ResumeAnalysisFact fact = new ResumeAnalysisFact();
                fact.setAnalysis(analysis);
                fact.setType(type);
                fact.setExtractedValue(value);
                fact.setNormalizedValue(orDefault(limit(extracted.normalizedValue(), 1000), value));
                fact.setEvidence(orDefault(limit(extracted.evidence(), 1000), value));
                fact.setSourceSection(orDefault(limit(extracted.sourceSection(), 80), "unknown"));
                fact.setSourceReference(limit(extracted.sourceReference(), 80));
                fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED);
                fact.setEvidenceTier(EvidenceTier.RESUME_STATED);
                fact.setSpanStart(nonNegative(extracted.spanStart()));
                fact.setSpanEnd(nonNegative(extracted.spanEnd()));
                fact.setAttributesJson(attributesJson(extracted.attributes()));
                factRepository.save(fact);
            }
        }
        analysis.setStatus(status);
        analysis.setParserVersion(limit(result.parserVersion(), 80));
        analysis.setSourceSha256(sha256);
        analysis.setErrorCode(safeErrorCode(result.errorCode()));
        analysis.setCompletedAt(UtcTime.now());
        analysis.setFlagsJson(ResumeFlags.toJson(objectMapper, result.flags()));
        analysis.setTimelineJson(withFacts ? timelineJson(result.timeline()) : null);
        analysisRepository.save(analysis);
        matchTriggerService.requestMatchForResume(analysis.getResume().getId());
    }

    /** Permanent failure of the current attempt (PROCESSING to FAILED). */
    public void fail(Long analysisId, String errorCode) {
        analysisRepository.findById(analysisId).ifPresent(analysis -> {
            if (analysis.getStatus() != ResumeAnalysisStatus.PROCESSING) return;
            analysis.setStatus(ResumeAnalysisStatus.FAILED);
            analysis.setErrorCode(safeErrorCode(errorCode));
            analysis.setCompletedAt(UtcTime.now());
            matchTriggerService.requestMatchForResume(analysis.getResume().getId());
        });
    }

    /** Transient failure: back to PENDING so the retried job can claim it again; errorCode tells the UI why. */
    public void releaseForRetry(Long analysisId, String errorCode) {
        analysisRepository.findById(analysisId).ifPresent(analysis -> {
            if (analysis.getStatus() != ResumeAnalysisStatus.PROCESSING) return;
            analysis.setStatus(ResumeAnalysisStatus.PENDING);
            analysis.setErrorCode(safeErrorCode(errorCode));
        });
    }

    /** Dead letter: an analysis still waiting for parsing becomes FAILED (and can be retried by users). */
    public void failIfUnfinished(Long analysisId, String errorCode) {
        analysisRepository.findById(analysisId).ifPresent(analysis -> {
            if (!CLAIMABLE.contains(analysis.getStatus())) return;
            analysis.setStatus(ResumeAnalysisStatus.FAILED);
            analysis.setErrorCode(safeErrorCode(errorCode));
            analysis.setCompletedAt(UtcTime.now());
            matchTriggerService.requestMatchForResume(analysis.getResume().getId());
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

    private void queueRetry(ResumeAnalysis analysis) {
        if (analysis.getStatus() != ResumeAnalysisStatus.FAILED) {
            throw new BadRequestException("Only failed resume analysis can be retried");
        }
        analysis.setStatus(ResumeAnalysisStatus.PENDING);
        analysis.setErrorCode(null);
        analysis.setCompletedAt(null);
        ResumeAnalysis saved = analysisRepository.save(analysis);
        backgroundJobService.enqueue(BackgroundJobType.PARSE_RESUME, saved.getId());
    }

    private ResumeAnalysisFactResponse factResponse(ResumeAnalysisFact fact, Long claimId) {
        JsonNode attributes = JsonText.read(objectMapper, fact.getAttributesJson());
        return ResumeAnalysisFactResponse.from(fact, claimId, attributes != null && attributes.isObject() ? attributes : null);
    }

    private String attributesJson(JsonNode attributes) {
        if (attributes == null || !attributes.isObject() || attributes.isEmpty()) {
            return null;
        }
        String json = attributes.toString();
        return json.length() > MAX_ATTRIBUTES_JSON ? null : json;
    }

    private String timelineJson(JsonNode timeline) {
        if (timeline == null || !timeline.isObject()) {
            return null;
        }
        String json = timeline.toString();
        return json.length() > MAX_TIMELINE_JSON ? null : json;
    }

    private ResumeAnalysisStatus parseStatus(String status) {
        try {
            ResumeAnalysisStatus parsed = ResumeAnalysisStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            return TERMINAL_FROM_PARSER.contains(parsed) ? parsed : ResumeAnalysisStatus.FAILED;
        } catch (RuntimeException ex) {
            return ResumeAnalysisStatus.FAILED;
        }
    }

    /** Unknown fact types are skipped. */
    private ResumeFactType factType(String type) {
        if (type == null) return null;
        try {
            return ResumeFactType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String safeErrorCode(String value) {
        if (value == null || value.isBlank()) return null;
        return value.matches("[A-Z0-9_]{1,60}") ? value : "ANALYSIS_FAILED";
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record WorkItem(Long analysisId, String storageKey, String filename, String contentType) { }
}
