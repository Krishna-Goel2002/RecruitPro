package com.recruitpro.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.application.dto.ApplicationCreateRequest;
import com.recruitpro.application.dto.ApplicationDetailResponse;
import com.recruitpro.application.dto.ApplicationResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.evidence.service.StoredMatchResults;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Candidate side of applications (plan 5.5): apply (queues matching, APPLICATION_RECEIVED), list, detail with
 * timeline and requirement verification, and withdrawal from SUBMITTED, SHORTLISTED or INTERVIEWING.
 */
@Service
@Transactional
public class ApplicationService {
    /** Statuses in which the candidate sees requirement verification (when the organisation shares it). */
    static final Set<ApplicationStatus> VERIFICATION_VISIBLE = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING);

    private final CandidateProfileService profileService;
    private final ApplicationRepository applicationRepository;
    private final ResumeRepository resumeRepository;
    private final JobRepository jobRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final JobRequirementRepository requirementRepository;
    private final OrganizationRepository organizationRepository;
    private final MatchTriggerService matchTriggerService;
    private final ApplicationNotifier notifier;
    private final ApplicationExtensions extensions;
    private final ObjectMapper objectMapper;
    /** S4: when true (prod default), candidates must verify their email address before applying. */
    private final boolean requireVerifiedEmailToApply;
    /** R2-M01: interviews and work samples still open when the candidate withdraws. */
    private CandidateDepartures departures;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDepartures(CandidateDepartures departures) {
        this.departures = departures;
    }

    public ApplicationService(CandidateProfileService profileService, ApplicationRepository applicationRepository,
                              ResumeRepository resumeRepository, JobRepository jobRepository,
                              ApplicationDecisionHistoryRepository historyRepository,
                              JobMatchRunRepository matchRunRepository, JobRequirementRepository requirementRepository,
                              OrganizationRepository organizationRepository, MatchTriggerService matchTriggerService,
                              ApplicationNotifier notifier, ApplicationExtensions extensions, ObjectMapper objectMapper,
                              @Value("${app.auth.require-verified-email-to-apply:false}") boolean requireVerifiedEmailToApply) {
        this.profileService = profileService;
        this.applicationRepository = applicationRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.historyRepository = historyRepository;
        this.matchRunRepository = matchRunRepository;
        this.requirementRepository = requirementRepository;
        this.organizationRepository = organizationRepository;
        this.matchTriggerService = matchTriggerService;
        this.notifier = notifier;
        this.extensions = extensions;
        this.objectMapper = objectMapper;
        this.requireVerifiedEmailToApply = requireVerifiedEmailToApply;
    }

    public ApplicationResponse apply(Long jobId, ApplicationCreateRequest request) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        if (requireVerifiedEmailToApply && candidate.getUser().getEmailVerifiedAt() == null)
            throw new ForbiddenException("Verify your email address before applying");
        if (request == null || request.resumeId() == null) throw new BadRequestException("An uploaded resume is required to apply");
        Resume resume = resumeRepository.findByIdAndCandidateId(request.resumeId(), candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        if (!job.isOpenForApplications(UtcTime.now()))
            throw new BadRequestException("Applications are accepted only for published jobs with an open deadline");
        if (applicationRepository.existsByCandidateIdAndJobId(candidate.getId(), jobId))
            throw new DuplicateResourceException("You have already applied to this job");
        Application application = new Application();
        application.setCandidate(candidate); application.setJob(job); application.setResume(resume);
        application.setStatus(ApplicationStatus.SUBMITTED); application.setAppliedAt(UtcTime.now());
        String coverLetter = request.coverLetter() == null ? null : request.coverLetter().trim();
        application.setCoverLetter(coverLetter == null || coverLetter.isEmpty() ? null : coverLetter);
        Application saved;
        try { saved = applicationRepository.saveAndFlush(application); }
        catch (DataIntegrityViolationException ex) { throw new DuplicateResourceException("You have already applied to this job"); }
        matchTriggerService.requestMatch(saved.getId());
        notifier.applicationReceived(saved);
        return ApplicationResponse.from(saved, organizationName(job));
    }

    /** Not read-only: {@code getOrProvisionMine()} may create a missing candidate profile. */
    public PageResponse<ApplicationResponse> listMine(int page, int size) {
        validatePage(page, size);
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Page<Application> result = applicationRepository.findAllByCandidateId(candidate.getId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt")));
        Set<Long> organizationIds = new HashSet<>();
        for (Application application : result.getContent()) organizationIds.add(application.getJob().getOrganizationId());
        organizationIds.remove(null);
        Map<Long, String> names = new HashMap<>();
        if (!organizationIds.isEmpty()) {
            for (Organization organization : organizationRepository.findAllById(organizationIds)) names.put(organization.getId(), organization.getName());
        }
        List<ApplicationResponse> content = result.getContent().stream()
                .map(a -> ApplicationResponse.from(a, names.get(a.getJob().getOrganizationId()))).toList();
        return PageResponse.<ApplicationResponse>builder().content(content)
                .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();
    }

    /** Not read-only: {@code getOrProvisionMine()} may create a missing candidate profile. Reads only otherwise. */
    public ApplicationDetailResponse getMine(Long id) {
        Application application = findOwned(id);
        Job job = application.getJob();
        Organization organization = organizationRepository.findById(job.getOrganizationId()).orElse(null);
        ApplicationResponse base = ApplicationResponse.from(application, organization == null ? null : organization.getName());

        List<ApplicationDetailResponse.TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new ApplicationDetailResponse.TimelineEntry(ApplicationStatus.SUBMITTED, application.getAppliedAt()));
        for (ApplicationDecisionHistory event : historyRepository.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId())) {
            if (event.getPreviousStatus() != event.getNewStatus()) {
                timeline.add(new ApplicationDetailResponse.TimelineEntry(event.getNewStatus(), event.getCreatedAt()));
            }
        }
        List<ApplicationDetailResponse.RequirementVerification> requirements = null;
        if (organization != null && organization.isShareVerificationStatus() && VERIFICATION_VISIBLE.contains(application.getStatus())) {
            requirements = verification(job, application.getId());
        }
        return ApplicationDetailResponse.of(base, timeline, requirements,
                extensions.candidateWorkSamples(application), extensions.candidateInterviews(application));
    }

    /** Candidate withdrawal (SUBMITTED, SHORTLISTED, INTERVIEWING); recorded in the decision history. */
    public ApplicationResponse withdraw(Long id) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Application application = applicationRepository.findOwnedForUpdate(id, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        ApplicationStatus previous = application.getStatus();
        if (!ApplicationTransitions.candidateMayWithdraw(previous))
            throw new BadRequestException("Only submitted, shortlisted or interviewing applications can be withdrawn");
        application.setStatus(ApplicationStatus.WITHDRAWN);
        application.setStatusChangedAt(UtcTime.now());
        Application saved = applicationRepository.saveAndFlush(application);
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(saved);
        event.setPreviousStatus(previous);
        event.setNewStatus(ApplicationStatus.WITHDRAWN);
        event.setActionType(ApplicationDecisionAction.WITHDRAW);
        event.setActor(candidate.getUser());
        event.setMatchRunId(matchRunRepository.findCurrentRunIds(saved.getId()).stream().findFirst().orElse(null));
        historyRepository.save(event);
        if (departures != null) {
            departures.candidateLeft(saved, false);
        }
        notifier.statusChanged(saved, ApplicationStatus.WITHDRAWN);
        return ApplicationResponse.from(saved, organizationName(saved.getJob()));
    }

    private List<ApplicationDetailResponse.RequirementVerification> verification(Job job, Long applicationId) {
        List<JobRequirement> requirements = requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(job.getId());
        Map<Long, AiMatchResponse.RequirementResult> results = new HashMap<>();
        JobMatchRun run = matchRunRepository.findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(applicationId).orElse(null);
        if (run != null) {
            for (AiMatchResponse.RequirementResult result : StoredMatchResults.read(objectMapper, run.getRequirementResultsJson()).requirements()) {
                results.putIfAbsent(result.requirementId(), result);
            }
        }
        List<ApplicationDetailResponse.RequirementVerification> list = new ArrayList<>();
        for (JobRequirement requirement : requirements) {
            list.add(new ApplicationDetailResponse.RequirementVerification(requirement.getId(), requirement.getType(),
                    requirement.getDescription(), requirement.isMandatory(), verificationOf(results.get(requirement.getId()))));
        }
        return list;
    }

    /** VERIFIED (matched at tier 1+), CLAIMED (matched only at tier 0) or NOT_VERIFIED. */
    static String verificationOf(AiMatchResponse.RequirementResult result) {
        if (result == null || !"MATCHED".equals(result.status())) {
            return "NOT_VERIFIED";
        }
        EvidenceTier tier = EvidenceTier.parse(result.tier());
        if (tier == null) {
            return "NOT_VERIFIED";
        }
        return tier.isVerified() ? "VERIFIED" : "CLAIMED";
    }

    private String organizationName(Job job) {
        if (job == null || job.getOrganizationId() == null) return null;
        return organizationRepository.findById(job.getOrganizationId()).map(Organization::getName).orElse(null);
    }

    private Application findOwned(Long id) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return applicationRepository.findByIdAndCandidateId(id, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
    }
}
