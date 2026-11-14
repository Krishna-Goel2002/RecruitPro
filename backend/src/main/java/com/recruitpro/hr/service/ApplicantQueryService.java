package com.recruitpro.hr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicantRow;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.application.service.ApplicationDetailExtension;
import com.recruitpro.application.service.ApplicationExtensions;
import com.recruitpro.application.service.ApplicationTransitions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.repository.AnalysisSummary;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.service.ResumeFlags;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.service.StoredMatchResults;
import com.recruitpro.hr.dto.*;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.repository.RunSummary;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.jobs.repository.LatestJobStatus;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * HR applicant review, read side (plan 5.4, C2, C3). Read-only: never calls the AI service and never writes
 * (H6, H8). Ranking is computed on the server over every applicant of the job (lightweight projections of the
 * applications and their current match runs), then paged in memory; only the page is enriched.
 */
@Service
@Transactional(readOnly = true)
public class ApplicantQueryService {

    public static final String SORT_RANK = "rank";
    public static final String SORT_APPLIED_AT = "appliedAt";
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<ResumeAnalysisStatus> USABLE_ANALYSIS = EnumSet.of(ResumeAnalysisStatus.COMPLETED, ResumeAnalysisStatus.PARTIAL);
    private static final Set<ResumeAnalysisStatus> WAITING_ANALYSIS = EnumSet.of(ResumeAnalysisStatus.PENDING, ResumeAnalysisStatus.PROCESSING);
    private static final Set<BackgroundJobStatus> ACTIVE_JOB = EnumSet.of(BackgroundJobStatus.PENDING, BackgroundJobStatus.RUNNING);
    private static final Comparator<LocalDateTime> NEWEST_FIRST_TIME = Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder());
    private static final Comparator<ApplicantRanking.Ranked> NEWEST_FIRST = (a, b) -> {
        int byTime = NEWEST_FIRST_TIME.compare(a.input().appliedAt(), b.input().appliedAt());
        return byTime != 0 ? byTime : Long.compare(b.input().applicationId(), a.input().applicationId());
    };

    private final HrJobAccessService jobAccess;
    private final SecurityUtils securityUtils;
    private final ApplicationRepository applicationRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final JobRequirementRepository requirementRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final BackgroundJobRepository backgroundJobRepository;
    private final OrganizationRepository organizationRepository;
    private final CandidateClaimRepository claimRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final ApplicationExtensions extensions;
    private final ObjectMapper objectMapper;

    public ApplicantQueryService(HrJobAccessService jobAccess, SecurityUtils securityUtils,
                                 ApplicationRepository applicationRepository, JobMatchRunRepository matchRunRepository,
                                 JobRequirementRepository requirementRepository, ResumeAnalysisRepository analysisRepository,
                                 BackgroundJobRepository backgroundJobRepository, OrganizationRepository organizationRepository,
                                 CandidateClaimRepository claimRepository,
                                 ApplicationDecisionHistoryRepository historyRepository, UserRepository userRepository,
                                 ApplicationExtensions extensions, ObjectMapper objectMapper) {
        this.jobAccess = jobAccess;
        this.securityUtils = securityUtils;
        this.applicationRepository = applicationRepository;
        this.matchRunRepository = matchRunRepository;
        this.requirementRepository = requirementRepository;
        this.analysisRepository = analysisRepository;
        this.backgroundJobRepository = backgroundJobRepository;
        this.organizationRepository = organizationRepository;
        this.claimRepository = claimRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.extensions = extensions;
        this.objectMapper = objectMapper;
    }

    /** GET /api/v1/hr/jobs/{jobId}/applicants: ranked (default) or newest first, filtered by status, size 1..100. */
    public PageResponse<ApplicantSummaryResponse> list(Long jobId, int page, int size, ApplicationStatus status, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String order = sort == null || sort.isBlank() ? SORT_RANK : sort.trim();
        if (!SORT_RANK.equals(order) && !SORT_APPLIED_AT.equals(order)) {
            throw new BadRequestException("sort must be 'rank' or 'appliedAt'");
        }
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        boolean blind = blindReview(job);
        boolean hasRequirements = requirementRepository.countByJobId(job.getId()) > 0;
        List<ApplicantRow> rows = status == null
                ? applicationRepository.findApplicantRows(job.getId())
                : applicationRepository.findApplicantRowsByStatus(job.getId(), status);
        Map<Long, RunSummary> runs = hasRequirements ? currentRunSummaries(job.getId()) : Map.of();
        List<ApplicantRanking.Ranked> ordered = new ArrayList<>(rank(rows, runs));
        if (SORT_APPLIED_AT.equals(order)) {
            ordered.sort(NEWEST_FIRST);
        }
        int total = ordered.size();
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        List<ApplicantRanking.Ranked> slice = ordered.subList(from, to);
        Map<Long, ApplicantRow> rowsById = new HashMap<>();
        for (ApplicantRow row : rows) {
            rowsById.put(row.applicationId(), row);
        }
        List<ApplicantSummaryResponse> content = summaries(job, blind, hasRequirements, slice, rowsById);
        int totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return PageResponse.<ApplicantSummaryResponse>builder()
                .content(content).page(page).size(size).totalElements(total).totalPages(totalPages)
                .first(page == 0).last(page >= totalPages - 1).build();
    }

    /** GET /api/v1/hr/jobs/{jobId}/applicants/{applicationId}. */
    public ApplicantDetailResponse detail(Long jobId, Long applicationId) {
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        CurrentUser viewer = securityUtils.currentUser();
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        return buildDetail(job, application, viewer);
    }

    /** GET …/history: every decision event, oldest first. */
    public List<DecisionEventResponse> history(Long jobId, Long applicationId) {
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        boolean revealed = isRevealed(application.getIdentityRevealedAt(), blindReview(job));
        return historyRepository.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).stream()
                .map(event -> DecisionEventResponse.from(event, revealed, blindName(application.getId()))).toList();
    }

    /**
     * Builds ApplicantDetail inside the caller's transaction (also used by the reveal endpoint after it has
     * changed the application). Reads only.
     */
    public ApplicantDetailResponse buildDetail(Job job, Application application, CurrentUser viewer) {
        boolean blind = blindReview(job);
        boolean revealed = isRevealed(application.getIdentityRevealedAt(), blind);
        boolean hasRequirements = requirementRepository.countByJobId(job.getId()) > 0;
        Long applicationId = application.getId();

        List<ApplicantRow> rows = applicationRepository.findApplicantRows(job.getId());
        Map<Long, RunSummary> runs = hasRequirements ? currentRunSummaries(job.getId()) : Map.of();
        ApplicantRanking.Ranked entry = null;
        for (ApplicantRanking.Ranked ranked : rank(rows, runs)) {
            if (ranked.input().applicationId() == applicationId) {
                entry = ranked;
                break;
            }
        }
        Map<Long, ApplicantRow> rowsById = new HashMap<>();
        for (ApplicantRow row : rows) {
            rowsById.put(row.applicationId(), row);
        }
        if (entry == null) {
            ApplicantRanking.Input input = new ApplicantRanking.Input(applicationId, application.getAppliedAt(), false, null, null, null);
            entry = new ApplicantRanking.Ranked(input, ApplicantRanking.RankGroup.UNSCORED, null);
            rowsById.put(applicationId, new ApplicantRow(applicationId, application.getStatus(), application.getAppliedAt(),
                    application.getStatusChangedAt(), application.getIdentityRevealedAt(), application.getResume().getId()));
        }
        ApplicantSummaryResponse summary = summaries(job, blind, hasRequirements, List.of(entry), rowsById).get(0);

        JobMatchRun run = hasRequirements
                ? matchRunRepository.findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(applicationId).orElse(null) : null;
        List<RequirementEvidenceResponse> requirements = requirementEvidence(job, run);

        CandidateProfile profile = application.getCandidate();
        List<CandidateClaimResponse> claims = claims(profile.getId(), revealed, viewer);
        CandidateIdentityResponse candidate = revealed ? identity(profile) : null;
        boolean manager = viewer.hasAnyRole(Role.ADMIN, Role.HR_MANAGER);
        List<ApplicationDecisionAction> allowed = manager ? ApplicationTransitions.allowedDecisions(application.getStatus()) : List.<ApplicationDecisionAction>of();
        boolean revealAllowed = manager && blind && application.getIdentityRevealedAt() == null
                && application.getStatus() != ApplicationStatus.SUBMITTED;
        List<DecisionEventResponse> history = historyRepository.findAllByApplicationIdOrderByCreatedAtAscIdAsc(applicationId).stream()
                .map(event -> DecisionEventResponse.from(event, revealed, blindName(applicationId))).toList();

        return ApplicantDetailResponse.of(summary, requirements,
                run == null ? null : run.getId(), run == null ? null : run.getAlgorithmVersion(),
                run == null ? null : run.getCalculatedAt(), claims, candidate,
                revealed ? application.getCoverLetter() : null, revealed,
                revealed ? application.getResume().getOriginalFilename() : null,
                extensions.staffWorkSamples(application), extensions.staffInterviews(application), history, allowed,
                revealAllowed);
    }

    // ---- shared helpers (also used by the decision service) ----

    public boolean blindReview(Job job) {
        return organizationRepository.findById(job.getOrganizationId()).map(o -> o.isBlindReview()).orElse(true);
    }

    public static boolean isRevealed(LocalDateTime identityRevealedAt, boolean blindReview) {
        return identityRevealedAt != null || !blindReview;
    }

    public static String blindName(Long applicationId) {
        return "Candidate #A-" + applicationId;
    }

    // ---- internals ----

    private Map<Long, RunSummary> currentRunSummaries(Long jobId) {
        Map<Long, RunSummary> runs = new HashMap<>();
        for (RunSummary run : matchRunRepository.findCurrentSummariesByJobId(jobId)) {
            runs.merge(run.applicationId(), run, (a, b) -> a.runId() >= b.runId() ? a : b);
        }
        return runs;
    }

    private static List<ApplicantRanking.Ranked> rank(List<ApplicantRow> rows, Map<Long, RunSummary> runs) {
        List<ApplicantRanking.Input> inputs = new ArrayList<>(rows.size());
        for (ApplicantRow row : rows) {
            RunSummary run = runs.get(row.applicationId());
            inputs.add(new ApplicantRanking.Input(row.applicationId(), row.appliedAt(), run != null,
                    run == null ? null : toDouble(run.verifiedCoverage()),
                    run == null ? null : toDouble(run.claimedCoverage()),
                    run == null ? null : run.knockoutsVerified()));
        }
        return ApplicantRanking.rank(inputs);
    }

    private List<ApplicantSummaryResponse> summaries(Job job, boolean blind, boolean hasRequirements,
                                                     List<ApplicantRanking.Ranked> slice, Map<Long, ApplicantRow> rowsById) {
        if (slice.isEmpty()) {
            return List.of();
        }
        List<Long> ids = slice.stream().map(entry -> entry.input().applicationId()).toList();
        Map<Long, Application> applications = new HashMap<>();
        for (Application application : applicationRepository.findAllWithCandidateByIdIn(ids)) {
            applications.put(application.getId(), application);
        }
        Set<Long> resumeIds = new HashSet<>();
        for (Long id : ids) {
            ApplicantRow row = rowsById.get(id);
            if (row != null && row.resumeId() != null) resumeIds.add(row.resumeId());
        }
        Map<Long, AnalysisSummary> analyses = new HashMap<>();
        if (!resumeIds.isEmpty()) {
            for (AnalysisSummary analysis : analysisRepository.findSummariesByResumeIds(resumeIds)) {
                analyses.put(analysis.resumeId(), analysis);
            }
        }
        Map<Long, JobMatchRun> runs = new HashMap<>();
        if (hasRequirements) {
            for (JobMatchRun run : matchRunRepository.findCurrentByApplicationIds(ids)) {
                runs.merge(run.getApplication().getId(), run, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        Map<Long, BackgroundJobStatus> latestJobs = new HashMap<>();
        for (LatestJobStatus latest : backgroundJobRepository.findLatestStatuses(BackgroundJobType.MATCH_APPLICATION, ids)) {
            latestJobs.put(latest.entityId(), latest.status());
        }
        Map<Long, ApplicationDetailExtension.ApplicantExtras> extras = extensions.applicantExtras(ids);

        List<ApplicantSummaryResponse> result = new ArrayList<>(slice.size());
        for (ApplicantRanking.Ranked entry : slice) {
            long id = entry.input().applicationId();
            ApplicantRow row = rowsById.get(id);
            Application application = applications.get(id);
            AnalysisSummary analysis = row == null ? null : analyses.get(row.resumeId());
            JobMatchRun run = runs.get(id);
            boolean revealed = isRevealed(row == null ? null : row.identityRevealedAt(), blind);
            String displayName = revealed && application != null ? fullName(application, id) : blindName(id);
            StoredMatchResults stored = run == null ? StoredMatchResults.EMPTY
                    : StoredMatchResults.read(objectMapper, run.getRequirementResultsJson());
            ApplicationDetailExtension.ApplicantExtras extra = extras.get(id);
            result.add(new ApplicantSummaryResponse(id, displayName, revealed,
                    row == null ? null : row.status(), row == null ? null : row.appliedAt(),
                    row == null ? null : row.statusChangedAt(),
                    analysis == null ? null : analysis.status().name(),
                    matchStatus(hasRequirements, analysis, run != null, latestJobs.get(id)),
                    entry.input().verifiedCoverage(), entry.input().claimedCoverage(), entry.input().knockoutsVerified(),
                    stored.unverifiedKnockouts(), entry.group().name(), entry.rank(),
                    analysis == null ? List.<String>of() : ResumeFlags.codes(objectMapper, analysis.flagsJson()),
                    extra == null ? null : extra.workSampleStatus(), extra == null ? 0 : extra.interviewCount()));
        }
        return result;
    }

    /**
     * NO_REQUIREMENTS (job has none) &gt; PENDING (a match is queued or running, or the resume is still being
     * analysed) &gt; READY (a current run exists) &gt; ANALYSIS_UNAVAILABLE (resume could not be analysed and there is
     * no other evidence) &gt; FAILED (matching did not produce a run; use rematch).
     */
    static String matchStatus(boolean hasRequirements, AnalysisSummary analysis, boolean hasRun, BackgroundJobStatus latestJob) {
        if (!hasRequirements) {
            return "NO_REQUIREMENTS";
        }
        if ((latestJob != null && ACTIVE_JOB.contains(latestJob))
                || (analysis != null && WAITING_ANALYSIS.contains(analysis.status()))) {
            return "PENDING";
        }
        if (hasRun) {
            return "READY";
        }
        if (analysis == null || !USABLE_ANALYSIS.contains(analysis.status())) {
            return "ANALYSIS_UNAVAILABLE";
        }
        return "FAILED";
    }

    /**
     * RequirementEvidence of every current requirement of the job from a match run (null: not assessed yet). Also
     * used by the interviewer's focus requirements (WP-BE-C). Reads only.
     */
    public List<RequirementEvidenceResponse> requirementEvidence(Job job, JobMatchRun run) {
        Map<Long, AiMatchResponse.RequirementResult> results = new HashMap<>();
        if (run != null) {
            for (AiMatchResponse.RequirementResult result : StoredMatchResults.read(objectMapper, run.getRequirementResultsJson()).requirements()) {
                results.putIfAbsent(result.requirementId(), result);
            }
        }
        List<RequirementEvidenceResponse> evidence = new ArrayList<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderById(job.getId())) {
            AiMatchResponse.RequirementResult result = results.get(requirement.getId());
            List<String> alternatives = List.copyOf(requirement.getAlternatives());
            if (result == null) {
                evidence.add(new RequirementEvidenceResponse(requirement.getId(), requirement.getType(), requirement.getDescription(),
                        requirement.isMandatory(), requirement.isKnockout(), requirement.getMinimumYears(), alternatives,
                        "NOT_OBSERVED", null, null, null,
                        run == null ? "Not assessed yet: matching has not run for this application."
                                : "Not assessed against this requirement yet; re-matching is queued or needed.",
                        null, null));
            } else {
                evidence.add(new RequirementEvidenceResponse(requirement.getId(), requirement.getType(), requirement.getDescription(),
                        requirement.isMandatory(), requirement.isKnockout(), requirement.getMinimumYears(), alternatives,
                        result.status() == null ? "NOT_OBSERVED" : result.status(), EvidenceTier.parse(result.tier()),
                        result.evidence(), result.sourceSection(), result.explanation() == null ? "" : result.explanation(),
                        result.verificationHint(), result.provenance()));
            }
        }
        return evidence;
    }

    private List<CandidateClaimResponse> claims(Long candidateId, boolean revealed, CurrentUser viewer) {
        List<CandidateClaim> claims = claimRepository.findAllByCandidateIdOrderByCreatedAtDescIdDesc(candidateId);
        Set<Long> verifierIds = new HashSet<>();
        for (CandidateClaim claim : claims) {
            if (claim.getVerifiedByUserId() != null) verifierIds.add(claim.getVerifiedByUserId());
        }
        Map<Long, Long> verifierOrganizations = new HashMap<>();
        if (!verifierIds.isEmpty()) {
            for (User user : userRepository.findAllById(verifierIds)) {
                if (user.getOrganizationId() != null) verifierOrganizations.put(user.getId(), user.getOrganizationId());
            }
        }
        List<CandidateClaimResponse> responses = new ArrayList<>(claims.size());
        for (CandidateClaim claim : claims) {
            boolean verifiedHere = claim.getVerifiedByUserId() == null
                    || Objects.equals(verifierOrganizations.get(claim.getVerifiedByUserId()), viewer.organizationId());
            responses.add(CandidateClaimResponse.forStaff(claim, revealed, verifiedHere));
        }
        return responses;
    }

    private static CandidateIdentityResponse identity(CandidateProfile profile) {
        User user = profile.getUser();
        return new CandidateIdentityResponse(user.getFullName(), user.getEmail(), profile.getCity(), profile.getCountry(),
                profile.getLinkedinUrl(), profile.getPortfolioUrl(), profile.getYearsOfExperience(),
                profile.getCurrentCompany(), profile.getCurrentDesignation());
    }

    private static String fullName(Application application, long id) {
        String name = application.getCandidate() == null || application.getCandidate().getUser() == null
                ? "" : application.getCandidate().getUser().getFullName();
        return name.isBlank() ? blindName(id) : name;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
