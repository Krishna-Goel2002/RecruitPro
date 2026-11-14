package com.recruitpro.evidence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads match inputs and stores match runs for the MATCH_APPLICATION job (D7, D8). The AI call happens between
 * {@link #prepare} and {@link #persist}, outside any transaction.
 */
@Service
public class MatchingService {

    public static final String DEFAULT_SCORE_LABEL = "Verified requirement evidence coverage (not a probability of success)";
    static final Set<ResumeAnalysisStatus> USABLE_ANALYSIS = EnumSet.of(ResumeAnalysisStatus.COMPLETED, ResumeAnalysisStatus.PARTIAL);
    static final Set<ResumeAnalysisStatus> WAITING_ANALYSIS = EnumSet.of(ResumeAnalysisStatus.PENDING, ResumeAnalysisStatus.PROCESSING);

    public static final String TRIGGER_INITIAL = "INITIAL";
    public static final String TRIGGER_REQUIREMENTS_CHANGED = "REQUIREMENTS_CHANGED";
    public static final String TRIGGER_RESUME_ANALYSIS_UPDATED = "RESUME_ANALYSIS_UPDATED";
    public static final String TRIGGER_EVIDENCE_CHANGED = "EVIDENCE_CHANGED";
    public static final String TRIGGER_RERUN = "RERUN";

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final JobRequirementRepository requirementRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;
    private final CandidateClaimRepository claimRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final UserRepository userRepository;
    private final ObjectProvider<EvidenceContributor> contributors;
    private final ObjectMapper objectMapper;
    private final MatchRequestBuilder requestBuilder;

    public MatchingService(ApplicationRepository applicationRepository, JobRepository jobRepository,
                           JobRequirementRepository requirementRepository, ResumeAnalysisRepository analysisRepository,
                           ResumeAnalysisFactRepository factRepository, CandidateClaimRepository claimRepository,
                           JobMatchRunRepository matchRunRepository, UserRepository userRepository,
                           ObjectProvider<EvidenceContributor> contributors, ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.requirementRepository = requirementRepository;
        this.analysisRepository = analysisRepository;
        this.factRepository = factRepository;
        this.claimRepository = claimRepository;
        this.matchRunRepository = matchRunRepository;
        this.userRepository = userRepository;
        this.contributors = contributors;
        this.objectMapper = objectMapper;
        this.requestBuilder = new MatchRequestBuilder(objectMapper);
    }

    /**
     * Builds the match request for an application, or returns null when there is nothing to match: the
     * application is missing or withdrawn/selected, the job has no requirements, the resume analysis is still
     * running (its completion queues a new match), or there is no usable evidence at all (unparseable resume and
     * neither claims nor contributed evidence).
     */
    @Transactional(readOnly = true)
    public PreparedMatch prepare(long applicationId) {
        Application application = applicationRepository.findById(applicationId).orElse(null);
        if (application == null || !MatchTriggerService.MATCHABLE.contains(application.getStatus())) {
            return null;
        }
        Job job = application.getJob();
        List<JobRequirement> requirements = requirementRepository.findByJobIdOrderById(job.getId());
        if (requirements.isEmpty()) {
            return null;
        }
        ResumeAnalysis analysis = analysisRepository.findByResumeId(application.getResume().getId()).orElse(null);
        if (analysis == null || WAITING_ANALYSIS.contains(analysis.getStatus())) {
            return null;
        }
        boolean usable = USABLE_ANALYSIS.contains(analysis.getStatus());
        List<ResumeAnalysisFact> facts = usable ? factRepository.findAllByAnalysisIdOrderById(analysis.getId()) : List.of();
        List<CandidateClaim> candidateClaims = claimRepository.findAllByCandidateIdOrderByIdAsc(application.getCandidate().getId());
        Map<Long, Long> verifierOrganizations = verifierOrganizations(candidateClaims);
        List<ClaimEvidence> claims = new ArrayList<>();
        for (CandidateClaim claim : candidateClaims) {
            ClaimEvidence evidence = ClaimEvidence.forOrganization(claim,
                    claim.getVerifiedByUserId() == null ? null : verifierOrganizations.get(claim.getVerifiedByUserId()),
                    job.getOrganizationId());
            if (!evidence.rejected()) {
                claims.add(evidence);
            }
        }
        List<AiMatchRequest.Fact> contributed = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> {
            List<AiMatchRequest.Fact> extra = contributor.contribute(application, requirements);
            if (extra != null) {
                contributed.addAll(extra);
            }
        });
        if (!usable && claims.isEmpty() && contributed.isEmpty()) {
            return null;
        }
        JsonNode timeline = usable ? JsonText.read(objectMapper, analysis.getTimelineJson()) : null;
        AiMatchRequest request = requestBuilder.build(requirements, facts, claims, contributed, timeline);
        return new PreparedMatch(application.getId(), job.getId(), analysis.getId(), analysis.getCompletedAt(),
                job.getRequirementsVersion(), request, fingerprint(request), UtcTime.now());
    }

    /**
     * Appends a run and makes it current, in one transaction with the application row locked (D8). The run's
     * {@code calculated_at} is the time its inputs were read. A run built from older inputs than the current run
     * (an older requirements version, or the same version read earlier, e.g. two overlapping jobs) is kept but not
     * made current. Nothing is stored when the application was withdrawn or selected meanwhile.
     */
    @Transactional
    public JobMatchRun persist(PreparedMatch prepared, AiMatchResponse response) {
        Application application = applicationRepository.lockById(prepared.applicationId()).orElse(null);
        if (application == null || !MatchTriggerService.MATCHABLE.contains(application.getStatus())) {
            return null;
        }
        LocalDateTime snapshot = prepared.preparedAt() == null ? UtcTime.now() : prepared.preparedAt();
        List<JobMatchRun> currentRuns = matchRunRepository.findAllByApplicationIdAndCurrentRunTrue(application.getId());
        JobMatchRun previous = currentRuns.stream().max(Comparator.comparing(JobMatchRun::getId)).orElse(null);
        boolean makeCurrent = previous == null
                || prepared.requirementsVersion() > previous.getRequirementsVersion()
                || (prepared.requirementsVersion() == previous.getRequirementsVersion()
                    && (previous.getCalculatedAt() == null || !previous.getCalculatedAt().isAfter(snapshot)));
        if (makeCurrent) {
            for (JobMatchRun run : currentRuns) {
                run.setCurrentRun(false);
            }
        }
        JobMatchRun run = new JobMatchRun();
        run.setApplication(application);
        run.setJob(jobRepository.getReferenceById(prepared.jobId()));
        run.setResumeAnalysis(analysisRepository.getReferenceById(prepared.resumeAnalysisId()));
        run.setInputFingerprint(prepared.fingerprint());
        run.setAlgorithmVersion(limit(response.algorithmVersion() == null ? "unknown" : response.algorithmVersion(), 80));
        run.setScoreLabel(limit(response.scoreLabel() == null || response.scoreLabel().isBlank()
                ? DEFAULT_SCORE_LABEL : response.scoreLabel(), 255));
        BigDecimal verified = coverage(response.verifiedCoverage());
        run.setVerifiedCoverage(verified);
        run.setObservedEvidenceCoverage(verified);
        run.setClaimedCoverage(coverage(response.claimedCoverage()));
        run.setKnockoutsVerified(response.knockoutsVerified());
        run.setRequirementResultsJson(new StoredMatchResults(
                response.requirements() == null ? List.of() : response.requirements(),
                response.unverifiedKnockouts() == null ? List.of() : response.unverifiedKnockouts()).write(objectMapper));
        run.setCalculatedAt(snapshot);
        run.setCurrentRun(makeCurrent);
        run.setRequirementsVersion(prepared.requirementsVersion());
        run.setTriggerReason(triggerReason(previous, prepared));
        return matchRunRepository.save(run);
    }

    private Map<Long, Long> verifierOrganizations(List<CandidateClaim> claims) {
        Set<Long> verifierIds = new HashSet<>();
        for (CandidateClaim claim : claims) {
            if (claim.getVerifiedByUserId() != null) {
                verifierIds.add(claim.getVerifiedByUserId());
            }
        }
        Map<Long, Long> organizations = new HashMap<>();
        if (!verifierIds.isEmpty()) {
            for (User user : userRepository.findAllById(verifierIds)) {
                if (user.getOrganizationId() != null) {
                    organizations.put(user.getId(), user.getOrganizationId());
                }
            }
        }
        return organizations;
    }

    /** Why this run happened, derived by comparing with the previous current run. */
    static String triggerReason(JobMatchRun previous, PreparedMatch prepared) {
        if (previous == null) {
            return TRIGGER_INITIAL;
        }
        if (previous.getRequirementsVersion() != prepared.requirementsVersion()) {
            return TRIGGER_REQUIREMENTS_CHANGED;
        }
        boolean otherAnalysis = previous.getResumeAnalysis() != null
                && previous.getResumeAnalysis().getId() != null
                && previous.getResumeAnalysis().getId() != prepared.resumeAnalysisId();
        boolean analysisNewer = prepared.analysisCompletedAt() != null && previous.getCalculatedAt() != null
                && prepared.analysisCompletedAt().isAfter(previous.getCalculatedAt());
        if (otherAnalysis || analysisNewer) {
            return TRIGGER_RESUME_ANALYSIS_UPDATED;
        }
        if (!prepared.fingerprint().equals(previous.getInputFingerprint())) {
            return TRIGGER_EVIDENCE_CHANGED;
        }
        return TRIGGER_RERUN;
    }

    String fingerprint(AiMatchRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(JsonText.write(objectMapper, request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    static BigDecimal coverage(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(100.0, value));
        return BigDecimal.valueOf(clamped).setScale(1, RoundingMode.HALF_UP);
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
