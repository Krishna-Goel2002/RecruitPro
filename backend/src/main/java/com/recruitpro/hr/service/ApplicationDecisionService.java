package com.recruitpro.hr.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.application.service.ApplicationNotifier;
import com.recruitpro.application.service.ApplicationTransitions;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.dto.ClaimVerificationRequest;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.dto.ApplicantDetailResponse;
import com.recruitpro.hr.dto.ApplicationCommentRequest;
import com.recruitpro.hr.dto.DecisionEventResponse;
import com.recruitpro.hr.dto.DecisionRequest;
import com.recruitpro.hr.dto.RematchResponse;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

/**
 * HR applicant review, write side (plan 3.4 and 5.4, C3, D4, D8). Decisions lock the application row with a plain
 * {@code SELECT … FOR UPDATE} (no joins) and record the current match run. Shortlisting reveals the identity
 * (IDENTITY_REVEALED audit + history row). Every status change notifies the candidate.
 */
@Service
@Transactional
public class ApplicationDecisionService {

    static final String AUTO_REVEAL_NOTE = "Identity revealed automatically on shortlist";
    static final String MANUAL_REVEAL_NOTE = "Identity revealed by a reviewer";

    private final HrJobAccessService jobAccess;
    private final SecurityUtils securityUtils;
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final JobRequirementRepository requirementRepository;
    private final UserRepository userRepository;
    private final CandidateClaimRepository claimRepository;
    private final AuditService auditService;
    private final ApplicationNotifier notifier;
    private final MatchTriggerService matchTriggerService;
    private final ResumeIntelligenceService resumeIntelligenceService;
    private final ResumeStorage resumeStorage;
    private final ApplicantQueryService queryService;

    public ApplicationDecisionService(HrJobAccessService jobAccess, SecurityUtils securityUtils,
                                      ApplicationRepository applicationRepository,
                                      ApplicationDecisionHistoryRepository historyRepository,
                                      JobMatchRunRepository matchRunRepository, JobRequirementRepository requirementRepository,
                                      UserRepository userRepository, CandidateClaimRepository claimRepository,
                                      AuditService auditService, ApplicationNotifier notifier,
                                      MatchTriggerService matchTriggerService,
                                      ResumeIntelligenceService resumeIntelligenceService, ResumeStorage resumeStorage,
                                      ApplicantQueryService queryService) {
        this.jobAccess = jobAccess;
        this.securityUtils = securityUtils;
        this.applicationRepository = applicationRepository;
        this.historyRepository = historyRepository;
        this.matchRunRepository = matchRunRepository;
        this.requirementRepository = requirementRepository;
        this.userRepository = userRepository;
        this.claimRepository = claimRepository;
        this.auditService = auditService;
        this.notifier = notifier;
        this.matchTriggerService = matchTriggerService;
        this.resumeIntelligenceService = resumeIntelligenceService;
        this.resumeStorage = resumeStorage;
        this.queryService = queryService;
    }

    /** POST …/decisions (and the legacy /shortlist and /reject aliases). ADMIN and HR_MANAGER. */
    public DecisionEventResponse decide(Long jobId, Long applicationId, DecisionRequest request) {
        if (request == null || request.action() == null) {
            throw new BadRequestException("action is required");
        }
        ApplicationDecisionAction action = request.action();
        if (!action.isDecision()) {
            throw new BadRequestException("Unsupported decision: " + action);
        }
        Job job = jobAccess.requireManageAccess(jobId);
        Application application = lockForReview(job, applicationId);
        ApplicationStatus previous = application.getStatus();
        ApplicationStatus target = ApplicationTransitions.target(previous, action);
        if (target == null) {
            throw new BadRequestException("Cannot " + describe(action) + " an application that is " + previous);
        }
        String comment = normalize(request.comment());
        if (ApplicationTransitions.requiresComment(action) && comment == null) {
            throw new BadRequestException("A comment is required to " + describe(action) + " an application");
        }
        User actor = currentActor();
        Long matchRunId = currentRunId(application.getId());

        application.setStatus(target);
        application.setStatusChangedAt(UtcTime.now());
        if (comment != null) {
            application.setReviewComment(comment);
        }
        applicationRepository.saveAndFlush(application);
        ApplicationDecisionHistory event = historyRepository.saveAndFlush(
                event(application, previous, target, action, actor, comment, matchRunId));
        if (action == ApplicationDecisionAction.SHORTLIST && application.getIdentityRevealedAt() == null) {
            if (queryService.blindReview(job)) {
                revealIdentity(application, actor, matchRunId, AUTO_REVEAL_NOTE);
            } else {
                markRevealed(application, actor);
            }
        }
        auditService.record(AuditActions.APPLICATION_DECISION, "APPLICATION", String.valueOf(application.getId()),
                "Application #" + application.getId() + " of job #" + job.getId() + ": " + previous + " -> " + target
                        + " (" + action + ")" + (matchRunId == null ? ", no match run" : ", match run #" + matchRunId));
        notifier.statusChanged(application, target);
        return DecisionEventResponse.from(event, true, ApplicantQueryService.blindName(application.getId()));
    }

    /** PUT …/review-comment. ADMIN, HR_MANAGER and RECRUITER; any status. */
    public DecisionEventResponse updateComment(Long jobId, Long applicationId, ApplicationCommentRequest request) {
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        Application application = lockForReview(job, applicationId);
        String comment = normalize(request == null ? null : request.comment());
        User actor = currentActor();
        application.setReviewComment(comment);
        applicationRepository.saveAndFlush(application);
        ApplicationDecisionHistory event = historyRepository.saveAndFlush(event(application, application.getStatus(),
                application.getStatus(), ApplicationDecisionAction.COMMENT_UPDATED, actor, comment, currentRunId(application.getId())));
        boolean revealed = ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), queryService.blindReview(job));
        return DecisionEventResponse.from(event, revealed, ApplicantQueryService.blindName(application.getId()));
    }

    /**
     * POST …/reveal (ADMIN, HR_MANAGER): allowed when the application is no longer SUBMITTED or the organisation
     * has blind review off; idempotent once revealed. Under blind review it records IDENTITY_REVEALED (history and
     * audit); with blind review off the identity is already visible and only the reveal time is kept. Returns
     * ApplicantDetail.
     */
    public ApplicantDetailResponse reveal(Long jobId, Long applicationId) {
        Job job = jobAccess.requireManageAccess(jobId);
        Application application = lockForReview(job, applicationId);
        if (application.getIdentityRevealedAt() == null) {
            boolean blind = queryService.blindReview(job);
            if (blind && application.getStatus() == ApplicationStatus.SUBMITTED) {
                throw new BadRequestException("Blind review: the identity of a submitted application is revealed when it is shortlisted");
            }
            if (blind) {
                revealIdentity(application, currentActor(), currentRunId(application.getId()), MANUAL_REVEAL_NOTE);
            } else {
                // Blind review is off: the identity is already visible; keep it visible if blind review is turned on.
                markRevealed(application, currentActor());
            }
            applicationRepository.saveAndFlush(application);
        }
        return queryService.buildDetail(job, application, securityUtils.currentUser());
    }

    /** GET …/resume (ADMIN, HR_MANAGER, RECRUITER): only after reveal; audited (RESUME_DOWNLOADED_BY_STAFF). */
    public ResumeFile downloadResume(Long jobId, Long applicationId) {
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (!ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), queryService.blindReview(job))) {
            throw new ForbiddenException("The resume is available once the candidate's identity is revealed");
        }
        Resume resume = application.getResume();
        byte[] bytes;
        try {
            bytes = resumeStorage.load(resume.getStorageKey());
        } catch (NoSuchFileException ex) {
            throw new ResourceNotFoundException("Resume file not found");
        } catch (IOException ex) {
            throw new IllegalStateException("Resume file could not be read");
        }
        auditService.record(AuditActions.RESUME_DOWNLOADED_BY_STAFF, "APPLICATION", String.valueOf(application.getId()),
                "Resume #" + resume.getId() + " of application #" + application.getId() + " downloaded");
        String extension = resume.getFileExtension() == null ? "bin" : resume.getFileExtension().toLowerCase(Locale.ROOT);
        return new ResumeFile("application-" + application.getId() + "-resume." + extension, resume.getContentType(), bytes);
    }

    /**
     * POST …/claims/{claimId}/verification (ADMIN, HR_MANAGER, RECRUITER): verified=true corroborates the claim
     * (tier CORROBORATED), false rejects it (SELF_CLAIMED with a verification time; no longer used for matching).
     * The verification applies to this organisation only (D3). Audited (CLAIM_VERIFIED); re-matches the candidate's
     * applications to this organisation's jobs.
     */
    public CandidateClaimResponse verifyClaim(Long jobId, Long applicationId, Long claimId, ClaimVerificationRequest request) {
        if (request == null || request.verified() == null) {
            throw new BadRequestException("verified is required");
        }
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        Long candidateId = application.getCandidate().getId();
        CandidateClaim claim = claimRepository.findByIdAndCandidateId(claimId, candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found"));
        CurrentUser actor = securityUtils.currentUser();
        boolean verified = request.verified();
        if (verified) {
            if (claim.getTier() == null || claim.getTier().level() < EvidenceTier.CORROBORATED.level()) {
                claim.setTier(EvidenceTier.CORROBORATED);
            }
        } else {
            claim.setTier(EvidenceTier.SELF_CLAIMED);
        }
        claim.setVerifiedByUserId(actor.userId());
        claim.setVerifiedAt(UtcTime.now());
        String note = normalize(request.note());
        claim.setVerificationNote(note != null && note.length() > 500 ? note.substring(0, 500) : note);
        CandidateClaim saved = claimRepository.save(claim);
        auditService.record(AuditActions.CLAIM_VERIFIED, "CANDIDATE_CLAIM", String.valueOf(saved.getId()),
                "Claim #" + saved.getId() + " " + (verified ? "verified" : "rejected") + " while reviewing application #"
                        + application.getId());
        // The verification counts only for this organisation (ClaimEvidence), so only its applications are re-matched.
        matchTriggerService.requestMatchForCandidateInOrganization(candidateId, job.getOrganizationId());
        boolean revealed = ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), queryService.blindReview(job));
        return CandidateClaimResponse.forStaff(saved, revealed, true);
    }

    /** PATCH …/analysis/retry (ADMIN, HR_MANAGER): re-queues a failed resume analysis. */
    public void retryAnalysis(Long jobId, Long applicationId) {
        Job job = jobAccess.requireManageAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found for this job"));
        if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
            throw new BadRequestException("Withdrawn applications cannot be retried");
        }
        resumeIntelligenceService.retryForHr(application.getResume().getId());
    }

    /** POST /api/v1/hr/jobs/{jobId}/rematch (ADMIN, HR_MANAGER). */
    public RematchResponse rematch(Long jobId) {
        Job job = jobAccess.requireManageAccess(jobId);
        if (requirementRepository.countByJobId(job.getId()) == 0) {
            return new RematchResponse(0);
        }
        return new RematchResponse(matchTriggerService.requestMatchForJob(job.getId()));
    }

    // ---- internals ----

    private Application lockForReview(Job job, Long applicationId) {
        return applicationRepository.findForReview(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }

    private void revealIdentity(Application application, User actor, Long matchRunId, String note) {
        markRevealed(application, actor);
        historyRepository.save(event(application, application.getStatus(), application.getStatus(),
                ApplicationDecisionAction.IDENTITY_REVEALED, actor, note, matchRunId));
        auditService.record(AuditActions.IDENTITY_REVEALED, "APPLICATION", String.valueOf(application.getId()),
                note + " (application #" + application.getId() + ", status " + application.getStatus() + ")");
    }

    /** Records when and by whom the identity became visible, without history or audit (blind review off). */
    private static void markRevealed(Application application, User actor) {
        application.setIdentityRevealedAt(UtcTime.now());
        application.setIdentityRevealedById(actor.getId());
    }

    private User currentActor() {
        CurrentUser current = securityUtils.currentUser();
        return userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
    }

    private Long currentRunId(Long applicationId) {
        return matchRunRepository.findCurrentRunIds(applicationId).stream().findFirst().orElse(null);
    }

    private static ApplicationDecisionHistory event(Application application, ApplicationStatus previous, ApplicationStatus next,
                                                    ApplicationDecisionAction action, User actor, String comment, Long matchRunId) {
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(application);
        event.setPreviousStatus(previous);
        event.setNewStatus(next);
        event.setActionType(action);
        event.setActor(actor);
        event.setComment(comment);
        event.setMatchRunId(matchRunId);
        return event;
    }

    private static String describe(ApplicationDecisionAction action) {
        return switch (action) {
            case SHORTLIST -> "shortlist";
            case REJECT -> "reject";
            case UNDO_SHORTLIST -> "undo the shortlisting of";
            case REOPEN -> "reopen";
            case MOVE_TO_INTERVIEW -> "move to interview";
            case SELECT -> "select";
            case COMMENT_UPDATED, WITHDRAW, IDENTITY_REVEALED -> action.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Staff resume download. */
    public record ResumeFile(String filename, String contentType, byte[] bytes) { }
}
