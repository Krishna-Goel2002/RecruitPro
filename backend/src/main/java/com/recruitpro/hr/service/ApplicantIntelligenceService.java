package com.recruitpro.hr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiBatchMatchRequest;
import com.recruitpro.ai.dto.AiBatchMatchResponse;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.dto.ApplicantIntelligenceResponse;
import com.recruitpro.hr.dto.RequirementMatchResponse;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicantIntelligenceService {
    private static final String CURRENT_ALGORITHM_VERSION = "evidence-coverage-2";
    private static final TypeReference<List<RequirementMatchResponse>> REQUIREMENT_RESULTS_TYPE = new TypeReference<>() { };

    private final JobRequirementRepository requirementRepository;
    private final ApplicationRepository applicationRepository;
    private final ResumeIntelligenceService resumeIntelligenceService;
    private final ResumeAnalysisFactRepository factRepository;
    private final AiServiceClient aiServiceClient;
    private final JobMatchRunRepository matchRunRepository;
    private final ObjectMapper objectMapper;
    private final HrJobAccessService jobAccess;

    @Transactional
    public PageResponse<ApplicantIntelligenceResponse> list(Long jobId, int page, int size) {
        return list(jobId, page, size, null);
    }

    @Transactional
    public PageResponse<ApplicantIntelligenceResponse> list(Long jobId, int page, int size, ApplicationStatus status) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and 50");
        }
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        List<JobRequirement> requirements = requirementRepository.findByJobIdOrderById(job.getId());
        List<AiMatchRequest.Requirement> aiRequirements = requirements.stream()
                .map(req -> new AiMatchRequest.Requirement(req.getId(), req.getType().name(), req.getDescription(), req.isMandatory(), req.getMinimumYears()))
                .toList();
        PageRequest requestPage = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt"));
        Page<Application> applications = status == null
                ? applicationRepository.findAllByJobId(job.getId(), requestPage)
                : applicationRepository.findAllByJobIdAndStatus(job.getId(), status, requestPage);

        Map<Long, ApplicantContext> contexts = new LinkedHashMap<>();
        Map<Long, MatchView> matches = new HashMap<>();
        List<AiBatchMatchRequest.Candidate> candidatesToMatch = new ArrayList<>();
        String matchingError = requirements.isEmpty() ? "NO_STRUCTURED_REQUIREMENTS" : null;
        for (Application application : applications.getContent()) {
            ResumeAnalysis analysis = resumeIntelligenceService.findByResume(application.getResume().getId()).orElse(null);
            if (analysis == null) continue;
            List<AiMatchRequest.Fact> facts = List.of();
            String fingerprint = null;
            AiMatchRequest request = null;
            if (!requirements.isEmpty() && (analysis.getStatus() == ResumeAnalysisStatus.COMPLETED || analysis.getStatus() == ResumeAnalysisStatus.PARTIAL)) {
                facts = factRepository.findAllByAnalysisIdOrderById(analysis.getId()).stream()
                        .filter(fact -> fact.getType() != ResumeFactType.EMAIL && fact.getType() != ResumeFactType.PHONE)
                        .map(this::aiFact).toList();
                request = new AiMatchRequest(aiRequirements, facts);
                fingerprint = fingerprint(application, analysis, request);
                MatchView cached = loadCurrentRun(application, job, analysis, fingerprint);
                if (cached != null) {
                    matches.put(application.getId(), cached);
                } else {
                    candidatesToMatch.add(new AiBatchMatchRequest.Candidate(application.getId(), facts));
                }
            }
            contexts.put(application.getId(), new ApplicantContext(application, analysis, request, fingerprint));
        }

        if (!requirements.isEmpty() && !candidatesToMatch.isEmpty()) {
            try {
                AiBatchMatchResponse response = aiServiceClient.matchBatch(new AiBatchMatchRequest(aiRequirements, candidatesToMatch));
                if (response != null && response.candidates() != null) {
                    for (AiBatchMatchResponse.Candidate candidate : response.candidates()) {
                        ApplicantContext context = contexts.get(candidate.applicationId());
                        if (context == null || context.request() == null || candidate.requirements() == null || candidate.algorithmVersion() == null) continue;
                        List<RequirementMatchResponse> results = requirementResults(requirements, candidate);
                        MatchView view = new MatchView(candidate.score(), candidate.scoreLabel(), candidate.algorithmVersion(), results);
                        matches.put(candidate.applicationId(), view);
                        persistRun(context, job, view);
                    }
                }
            } catch (RestClientException | IllegalStateException ex) {
                matchingError = "MATCHING_UNAVAILABLE";
            }
        }

        final String matchingFailure = matchingError;
        List<ApplicantIntelligenceResponse> content = applications.getContent().stream().map(application -> {
            ApplicantContext context = contexts.get(application.getId());
            ResumeAnalysis analysis = context == null ? null : context.analysis();
            MatchView match = matches.get(application.getId());
            String error = analysis == null ? "ANALYSIS_NOT_AVAILABLE" :
                    (matchingFailure != null && (analysis.getStatus() == ResumeAnalysisStatus.COMPLETED || analysis.getStatus() == ResumeAnalysisStatus.PARTIAL)
                            ? matchingFailure : null);
            return new ApplicantIntelligenceResponse(application.getId(), application.getStatus(), application.getAppliedAt(),
                    analysis == null ? null : analysis.getStatus(), match == null ? null : match.score(),
                    match == null ? null : match.scoreLabel(), match == null ? null : match.algorithmVersion(), error,
                    match == null ? List.of() : match.requirements());
        }).toList();

        return PageResponse.<ApplicantIntelligenceResponse>builder().content(content).page(applications.getNumber()).size(applications.getSize())
                .totalElements(applications.getTotalElements()).totalPages(applications.getTotalPages())
                .first(applications.isFirst()).last(applications.isLast()).build();
    }

    @Transactional
    public void retry(Long jobId, Long applicationId) {
        jobAccess.requireManageAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found for this job"));
        if (application.getStatus() != ApplicationStatus.SUBMITTED) throw new BadRequestException("Only submitted applications can be retried");
        resumeIntelligenceService.retryForHr(application.getResume().getId());
    }

    private MatchView loadCurrentRun(Application application, Job job, ResumeAnalysis analysis, String fingerprint) {
        return matchRunRepository.findByApplicationId(application.getId())
                .filter(run -> run.getJob().getId().equals(job.getId())
                        && run.getResumeAnalysis().getId().equals(analysis.getId())
                        && CURRENT_ALGORITHM_VERSION.equals(run.getAlgorithmVersion())
                        && fingerprint.equals(run.getInputFingerprint()))
                .map(run -> {
                    try {
                        List<RequirementMatchResponse> results = objectMapper.readValue(run.getRequirementResultsJson(), REQUIREMENT_RESULTS_TYPE);
                        return new MatchView(run.getObservedEvidenceCoverage() == null ? null : run.getObservedEvidenceCoverage().doubleValue(),
                                run.getScoreLabel(), run.getAlgorithmVersion(), results);
                    } catch (JsonProcessingException ex) {
                        return null;
                    }
                }).orElse(null);
    }

    private void persistRun(ApplicantContext context, Job job, MatchView match) {
        JobMatchRun run = matchRunRepository.findByApplicationId(context.application().getId()).orElseGet(JobMatchRun::new);
        run.setApplication(context.application());
        run.setJob(job);
        run.setResumeAnalysis(context.analysis());
        run.setInputFingerprint(context.fingerprint());
        run.setAlgorithmVersion(match.algorithmVersion());
        run.setObservedEvidenceCoverage(match.score() == null ? null : BigDecimal.valueOf(match.score()));
        run.setScoreLabel(match.scoreLabel() == null ? "Observed requirement evidence coverage" : match.scoreLabel());
        try {
            run.setRequirementResultsJson(objectMapper.writeValueAsString(match.requirements()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store match results");
        }
        run.setCalculatedAt(LocalDateTime.now());
        matchRunRepository.save(run);
    }

    private String fingerprint(Application application, ResumeAnalysis analysis, AiMatchRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((CURRENT_ALGORITHM_VERSION + ":" + application.getResume().getId() + ":" + analysis.getSourceSha256() + ":")
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new IllegalStateException("Unable to fingerprint match inputs");
        }
    }

    private List<RequirementMatchResponse> requirementResults(List<JobRequirement> requirements, AiBatchMatchResponse.Candidate match) {
        Map<Long, com.recruitpro.ai.dto.AiMatchResponse.RequirementMatch> byRequirement = match.requirements().stream()
                .collect(Collectors.toMap(com.recruitpro.ai.dto.AiMatchResponse.RequirementMatch::requirementId, item -> item, (first, second) -> first));
        List<RequirementMatchResponse> results = new ArrayList<>();
        for (JobRequirement req : requirements) {
            var item = byRequirement.get(req.getId());
            results.add(new RequirementMatchResponse(req.getId(), req.getType().name(), req.getDescription(),
                    req.isMandatory(), req.getMinimumYears(), item == null ? "NOT_OBSERVED" : item.status(),
                    item == null ? null : item.evidence(), item == null ? null : item.sourceSection(),
                    item == null ? null : item.provenance(), item == null ? "No extracted information was returned for this requirement." : item.explanation()));
        }
        return List.copyOf(results);
    }

    private AiMatchRequest.Fact aiFact(ResumeAnalysisFact fact) {
        boolean corrected = fact.getReviewStatus() == ResumeFactReviewStatus.CORRECTED && fact.getReviewedValue() != null;
        return new AiMatchRequest.Fact(fact.getId(), fact.getType().name(),
                fact.getReviewedValue() == null ? fact.getExtractedValue() : fact.getReviewedValue(),
                corrected ? fact.getReviewedValue() : fact.getEvidence(),
                corrected ? "candidate review" : fact.getSourceSection(), fact.getReviewStatus().name());
    }

    private record ApplicantContext(Application application, ResumeAnalysis analysis, AiMatchRequest request, String fingerprint) { }
    private record MatchView(Double score, String scoreLabel, String algorithmVersion, List<RequirementMatchResponse> requirements) { }
}
