package com.recruitpro.candidate.privacy;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.service.WorkSampleFileService;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.repository.UserTokenRepository;
import com.recruitpro.auth.service.RefreshTokenService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.verification.service.VerificationPrivacyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * DELETE /api/v1/candidates/me (plan 5.5, D14). After the password is confirmed (a wrong password is 400, not 401,
 * so the portal does not try a token refresh) the account is anonymised in one transaction:
 * <ul>
 *   <li>deleted: resume files (after commit), resume facts and parser flags/timeline, claims, the equal-opportunity
 *       survey, notifications, one-time tokens; every verification row (sources with their encrypted tokens, runs,
 *       evidence, verdicts and their reviews and labels, reference requests) and, after commit, verification
 *       documents and evidence snapshots (plan v2, WP-VBE); refresh tokens are revoked;</li>
 *   <li>anonymised: user (email {@code deleted-user-<id>@deleted.invalid}, name "Deleted Candidate", unusable
 *       password, disabled, {@code deleted_at}), profile fields, resume file names, cover letters, work-sample
 *       responses, and the actor email of the user's own audit rows;</li>
 *   <li>kept for audit: applications (open ones are withdrawn with a WITHDRAW history row), decision history, match
 *       runs, work-sample scores, interviews (scheduled ones cancelled) and scorecards, audit rows.</li>
 * </ul>
 * Audited as CANDIDATE_ACCOUNT_DELETED.
 */
@Service
public class CandidateAccountDeletionService {

    public static final String ANONYMOUS_FIRST_NAME = "Deleted";
    public static final String ANONYMOUS_LAST_NAME = "Candidate";
    static final String WITHDRAW_COMMENT = "Candidate account deleted";
    static final String REVOKE_REASON = "ACCOUNT_DELETED";
    private static final Set<ApplicationStatus> OPEN = EnumSet.of(ApplicationStatus.SUBMITTED, ApplicationStatus.SHORTLISTED,
            ApplicationStatus.INTERVIEWING);
    private static final Logger log = LoggerFactory.getLogger(CandidateAccountDeletionService.class);

    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;
    private final CandidateClaimRepository claimRepository;
    private final EeoResponseRepository eeoRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final WorkSampleAssignmentRepository assignmentRepository;
    private final InterviewRepository interviewRepository;
    private final NotificationRepository notificationRepository;
    private final UserTokenRepository userTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditEventRepository auditEventRepository;
    private final AuditService auditService;
    private final ResumeStorage resumeStorage;
    private final VerificationPrivacyService verificationPrivacyService;
    /** WP-PLAT: work-sample submission files (setter-injected to keep the constructor stable). */
    private WorkSampleFileService workSampleFileService;

    @Autowired
    public void setWorkSampleFileService(WorkSampleFileService workSampleFileService) {
        this.workSampleFileService = workSampleFileService;
    }

    public CandidateAccountDeletionService(SecurityUtils securityUtils, UserRepository userRepository,
                                           CandidateProfileRepository profileRepository, PasswordEncoder passwordEncoder,
                                           ResumeRepository resumeRepository, ResumeAnalysisRepository analysisRepository,
                                           ResumeAnalysisFactRepository factRepository,
                                           CandidateClaimRepository claimRepository, EeoResponseRepository eeoRepository,
                                           ApplicationRepository applicationRepository,
                                           ApplicationDecisionHistoryRepository historyRepository,
                                           JobMatchRunRepository matchRunRepository,
                                           WorkSampleAssignmentRepository assignmentRepository,
                                           InterviewRepository interviewRepository,
                                           NotificationRepository notificationRepository,
                                           UserTokenRepository userTokenRepository, RefreshTokenService refreshTokenService,
                                           AuditEventRepository auditEventRepository, AuditService auditService,
                                           ResumeStorage resumeStorage,
                                           VerificationPrivacyService verificationPrivacyService) {
        this.securityUtils = securityUtils;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.passwordEncoder = passwordEncoder;
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.factRepository = factRepository;
        this.claimRepository = claimRepository;
        this.eeoRepository = eeoRepository;
        this.applicationRepository = applicationRepository;
        this.historyRepository = historyRepository;
        this.matchRunRepository = matchRunRepository;
        this.assignmentRepository = assignmentRepository;
        this.interviewRepository = interviewRepository;
        this.notificationRepository = notificationRepository;
        this.userTokenRepository = userTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditEventRepository = auditEventRepository;
        this.auditService = auditService;
        this.resumeStorage = resumeStorage;
        this.verificationPrivacyService = verificationPrivacyService;
    }

    @Transactional
    public void deleteMyAccount(String password) {
        CurrentUser current = securityUtils.currentUser();
        if (current.role() != Role.CANDIDATE) {
            throw new ForbiddenException("Only candidate accounts can be deleted here");
        }
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        if (password == null || password.isEmpty() || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("The password is incorrect");
        }
        LocalDateTime now = UtcTime.now();
        String originalEmail = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
        String anonymousEmail = anonymousEmail(user.getId());
        Counts counts = new Counts();
        List<String> storageKeys = new ArrayList<>();

        CandidateProfile profile = profileRepository.findByUserId(user.getId()).orElse(null);
        if (profile != null) {
            Long candidateId = profile.getId();
            for (Application application : applicationRepository.lockAllByCandidateId(candidateId)) {
                counts.applications++;
                if (OPEN.contains(application.getStatus())) {
                    withdraw(application, user, now);
                    counts.withdrawn++;
                }
                application.setCoverLetter(null);
            }
            for (WorkSampleAssignment assignment : assignmentRepository.findAllForCandidate(candidateId)) {
                assignment.setResponseText(null);
                assignment.setResponseUrl(null);
                if (workSampleFileService != null) {
                    // WP-PLAT: submitted files are candidate content; rows now, stored files after commit.
                    workSampleFileService.deleteAll(assignment.getId());
                }
                if (assignment.getStatus() == WorkSampleAssignmentStatus.ASSIGNED
                        || assignment.getStatus() == WorkSampleAssignmentStatus.STARTED) {
                    assignment.setStatus(WorkSampleAssignmentStatus.CANCELLED);
                }
            }
            for (Interview interview : interviewRepository.findAllForCandidate(candidateId)) {
                if (interview.getStatus() == InterviewStatus.SCHEDULED) {
                    interview.setStatus(InterviewStatus.CANCELLED);
                }
            }
            List<CandidateClaim> claims = claimRepository.findAllByCandidateIdOrderByIdAsc(candidateId);
            counts.claims = claims.size();
            claimRepository.deleteAll(claims);
            VerificationPrivacyService.DeletionCounts verification = verificationPrivacyService.deleteAllForCandidate(candidateId);
            counts.verificationSources = verification.sources();
            counts.verdicts = verification.verdicts();
            counts.survey = eeoRepository.deleteAllByCandidate(candidateId);
            for (Resume resume : resumeRepository.findAllByCandidateIdOrderByUploadedAtDescIdDesc(candidateId)) {
                storageKeys.add(resume.getStorageKey());
                String extension = resume.getFileExtension() == null ? "bin" : resume.getFileExtension().toLowerCase(Locale.ROOT);
                resume.setOriginalFilename("deleted-resume." + extension);
                ResumeAnalysis analysis = analysisRepository.findByResumeId(resume.getId()).orElse(null);
                if (analysis != null) {
                    List<ResumeAnalysisFact> facts = factRepository.findAllByAnalysisIdOrderById(analysis.getId());
                    counts.facts += facts.size();
                    factRepository.deleteAll(facts);
                    analysis.setFlagsJson(null);
                    analysis.setTimelineJson(null);
                }
            }
            anonymiseProfile(profile);
        }

        notificationRepository.deleteAllForRecipient(user.getId(), originalEmail);
        userTokenRepository.deleteAllForUser(user.getId());
        refreshTokenService.revokeAllForUser(user.getId(), REVOKE_REASON);
        auditEventRepository.pseudonymiseActor(user.getId(), anonymousEmail);

        user.setEmail(anonymousEmail);
        user.setFirstName(ANONYMOUS_FIRST_NAME);
        user.setLastName(ANONYMOUS_LAST_NAME);
        // 64 random hex characters: unusable, and within BCrypt's 72-byte input limit.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")));
        user.setEnabled(false);
        user.setDeletedAt(now);
        user.setEmailVerifiedAt(null);
        user.setLockedUntil(null);
        user.setFailedLoginCount(0);
        userRepository.saveAndFlush(user);

        auditService.recordFor(null, user.getId(), anonymousEmail, AuditActions.CANDIDATE_ACCOUNT_DELETED, "USER",
                String.valueOf(user.getId()), "Candidate account deleted: " + counts.applications + " application(s) kept "
                        + "anonymised (" + counts.withdrawn + " withdrawn), " + storageKeys.size() + " resume file(s), "
                        + counts.facts + " fact(s), " + counts.claims + " claim(s), " + counts.verificationSources
                        + " verification source(s), " + counts.verdicts + " verdict(s) and " + counts.survey
                        + " survey response(s) deleted");
        deleteFilesAfterCommit(storageKeys);
    }

    public static String anonymousEmail(Long userId) {
        return "deleted-user-" + userId + "@deleted.invalid";
    }

    private void withdraw(Application application, User actor, LocalDateTime now) {
        ApplicationStatus previous = application.getStatus();
        application.setStatus(ApplicationStatus.WITHDRAWN);
        application.setStatusChangedAt(now);
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(application);
        event.setPreviousStatus(previous);
        event.setNewStatus(ApplicationStatus.WITHDRAWN);
        event.setActionType(ApplicationDecisionAction.WITHDRAW);
        event.setActor(actor);
        event.setComment(WITHDRAW_COMMENT);
        event.setMatchRunId(matchRunRepository.findCurrentRunIds(application.getId()).stream().findFirst().orElse(null));
        historyRepository.save(event);
    }

    private static void anonymiseProfile(CandidateProfile profile) {
        profile.setAddress(null);
        profile.setCity(null);
        profile.setState(null);
        profile.setCountry(null);
        profile.setZipCode(null);
        profile.setLinkedinUrl(null);
        profile.setPortfolioUrl(null);
        profile.setYearsOfExperience(null);
        profile.setCurrentCompany(null);
        profile.setCurrentDesignation(null);
        profile.setExpectedSalary(null);
        profile.setNoticePeriodDays(null);
        profile.setProfileSummary(null);
    }

    /** Files are removed only once the anonymisation is committed, so a rollback never loses a file. */
    private void deleteFilesAfterCommit(List<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        List<String> keys = List.copyOf(storageKeys);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteFiles(keys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFiles(keys);
            }
        });
    }

    private void deleteFiles(List<String> keys) {
        for (String key : keys) {
            try {
                resumeStorage.delete(key);
            } catch (IOException | RuntimeException ex) {
                log.warn("Could not delete a resume file of a deleted account: {}", ex.getClass().getSimpleName());
            }
        }
    }

    private static final class Counts {
        int applications;
        int withdrawn;
        int claims;
        int facts;
        int survey;
        int verificationSources;
        int verdicts;
    }
}
