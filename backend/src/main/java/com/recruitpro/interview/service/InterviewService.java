package com.recruitpro.interview.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.dto.DecisionRequest;
import com.recruitpro.hr.dto.RequirementEvidenceResponse;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.ApplicationDecisionService;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.interview.dto.InterviewDetailResponse;
import com.recruitpro.interview.dto.InterviewQuestion;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScheduleInterviewRequest;
import com.recruitpro.interview.dto.ScorecardRating;
import com.recruitpro.interview.dto.ScorecardRequest;
import com.recruitpro.interview.dto.UpdateInterviewRequest;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewScorecard;
import com.recruitpro.interview.entity.InterviewScorecardRevision;
import com.recruitpro.interview.dto.ScorecardResponse;
import com.recruitpro.interview.dto.ScorecardRevisionResponse;
import com.recruitpro.interview.repository.InterviewScorecardRevisionRepository;
import com.recruitpro.notification.service.NotificationAttachment;
import com.recruitpro.common.util.JsonText;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewKitRepository;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.interview.repository.InterviewScorecardRepository;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Interviews and scorecards (plan 5.6). Scheduling needs a SHORTLISTED or INTERVIEWING application and an
 * interviewer who is an enabled staff user of the job's organisation; the first interview of a SHORTLISTED
 * application moves it to INTERVIEWING through {@link ApplicationDecisionService} (history, audit and candidate
 * notification stay in one place). Only the assigned interviewer submits the scorecard (audited as
 * SCORECARD_SUBMITTED, re-matching queued); ADMIN and HR_MANAGER of the organisation may view any interview.
 */
@Service
@Transactional
public class InterviewService {

    /** Applications that may get an interview. */
    public static final Set<ApplicationStatus> SCHEDULABLE = EnumSet.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING);
    public static final int VERIFIED_MIN_RATING = 3;
    static final int MAX_NOTES = 2000;
    static final int MAX_SUMMARY = 4000;
    static final int MAX_QUESTION_ID = 40;
    static final int MIN_DURATION = 5;
    static final int MAX_DURATION = 480;
    static final int MAX_LOCATION = 500;

    private final HrJobAccessService jobAccess;
    private final SecurityUtils securityUtils;
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionService decisionService;
    private final ApplicantQueryService applicantQueryService;
    private final InterviewRepository interviewRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final InterviewKitRepository kitRepository;
    private final JobRequirementRepository requirementRepository;
    private final JobMatchRunRepository matchRunRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;
    private final NotificationService notificationService;
    private final InterviewViews views;
    private final InterviewCalendarService calendar;
    private final InterviewScorecardRevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;
    private final String hrPortalUrl;

    public InterviewService(HrJobAccessService jobAccess, SecurityUtils securityUtils,
                            ApplicationRepository applicationRepository, ApplicationDecisionService decisionService,
                            ApplicantQueryService applicantQueryService, InterviewRepository interviewRepository,
                            InterviewScorecardRepository scorecardRepository, InterviewKitRepository kitRepository,
                            JobRequirementRepository requirementRepository, JobMatchRunRepository matchRunRepository,
                            UserRepository userRepository, AuditService auditService,
                            MatchTriggerService matchTriggerService, NotificationService notificationService,
                            InterviewViews views, InterviewCalendarService calendar,
                            InterviewScorecardRevisionRepository revisionRepository, ObjectMapper objectMapper,
                            @Value("${app.frontend.hr-url:http://localhost:5174}") String hrPortalUrl) {
        this.jobAccess = jobAccess;
        this.securityUtils = securityUtils;
        this.applicationRepository = applicationRepository;
        this.decisionService = decisionService;
        this.applicantQueryService = applicantQueryService;
        this.interviewRepository = interviewRepository;
        this.scorecardRepository = scorecardRepository;
        this.kitRepository = kitRepository;
        this.requirementRepository = requirementRepository;
        this.matchRunRepository = matchRunRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
        this.notificationService = notificationService;
        this.views = views;
        this.calendar = calendar;
        this.revisionRepository = revisionRepository;
        this.objectMapper = objectMapper;
        this.hrPortalUrl = hrPortalUrl;
    }

    /**
     * POST /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews (ADMIN, HR_MANAGER). Notifies the candidate
     * (INTERVIEW_SCHEDULED) and the interviewer (INTERVIEW_ASSIGNED).
     */
    public InterviewResponse schedule(Long jobId, Long applicationId, ScheduleInterviewRequest request) {
        if (request == null || request.interviewerUserId() == null || request.scheduledAt() == null
                || request.durationMinutes() == null) {
            throw new BadRequestException("interviewerUserId, scheduledAt and durationMinutes are required");
        }
        Job job = jobAccess.requireManageAccess(jobId);
        Application application = applicationRepository.findForReview(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (!SCHEDULABLE.contains(application.getStatus())) {
            throw new BadRequestException("Interviews can be scheduled for shortlisted or interviewing applications only");
        }
        User interviewer = requireInterviewer(request.interviewerUserId(), job.getOrganizationId());
        LocalDateTime now = UtcTime.now();
        if (!request.scheduledAt().isAfter(now)) {
            throw new BadRequestException("scheduledAt must be in the future");
        }
        int duration = requireDuration(request.durationMinutes());
        String location = location(request.location());
        if (application.getStatus() == ApplicationStatus.SHORTLISTED) {
            decisionService.decide(job.getId(), application.getId(),
                    new DecisionRequest(ApplicationDecisionAction.MOVE_TO_INTERVIEW, null));
        }
        Interview interview = new Interview();
        interview.setApplication(application);
        interview.setInterviewerUserId(interviewer.getId());
        interview.setScheduledAt(request.scheduledAt());
        interview.setDurationMinutes(duration);
        interview.setLocation(location);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setCreatedByUserId(securityUtils.currentUser().userId());
        Interview saved = interviewRepository.saveAndFlush(interview);
        notifyScheduled(saved, application, job, interviewer);
        return views.staffView(saved);
    }

    /** GET …/applicants/{applicationId}/interviews (ADMIN, HR_MANAGER, RECRUITER), soonest first. */
    @Transactional(readOnly = true)
    public List<InterviewResponse> listForApplicant(Long jobId, Long applicationId) {
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        return views.staffViews(interviewRepository.findAllForApplication(application.getId()));
    }

    /**
     * PATCH /api/v1/interviews/{id} (ADMIN, HR_MANAGER): reschedule or cancel a SCHEDULED interview. A changed time
     * or place notifies candidate and interviewer again; a cancellation sends INTERVIEW_CANCELLED to both.
     */
    public InterviewResponse update(Long interviewId, UpdateInterviewRequest request) {
        if (request == null) {
            throw new BadRequestException("A request body is required");
        }
        CurrentUser caller = securityUtils.requireOrganizationMember();
        lockInOrganization(interviewId, caller);
        Interview interview = findInOrganization(interviewId, caller);
        Job job = interview.getApplication().getJob();
        jobAccess.requireManageAccess(job.getId());
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException("Only scheduled interviews can be changed");
        }
        Application application = interview.getApplication();
        User interviewer = userRepository.findById(interview.getInterviewerUserId()).orElse(null);
        if (request.status() != null && request.status() != InterviewStatus.SCHEDULED) {
            if (request.status() != InterviewStatus.CANCELLED) {
                throw new BadRequestException("status can only be set to CANCELLED");
            }
            interview.setStatus(InterviewStatus.CANCELLED);
            interview.setIcsSequence(interview.getIcsSequence() + 1);
            Interview saved = interviewRepository.saveAndFlush(interview);
            notifyCancelled(saved, application, job, interviewer);
            return views.staffView(saved);
        }
        boolean changed = false;
        if (request.scheduledAt() != null && !request.scheduledAt().equals(interview.getScheduledAt())) {
            if (!request.scheduledAt().isAfter(UtcTime.now())) {
                throw new BadRequestException("scheduledAt must be in the future");
            }
            interview.setScheduledAt(request.scheduledAt());
            changed = true;
        }
        if (request.durationMinutes() != null && request.durationMinutes() != interview.getDurationMinutes()) {
            interview.setDurationMinutes(requireDuration(request.durationMinutes()));
            changed = true;
        }
        if (request.location() != null) {
            String location = location(request.location());
            if (!Objects.equals(location, interview.getLocation())) {
                interview.setLocation(location);
                changed = true;
            }
        }
        if (changed) {
            // WP-PLAT: the same calendar event (UID) with a higher SEQUENCE replaces the earlier invitation.
            interview.setIcsSequence(interview.getIcsSequence() + 1);
        }
        Interview saved = interviewRepository.saveAndFlush(interview);
        if (changed) {
            notifyScheduled(saved, application, job, interviewer);
        }
        return views.staffView(saved);
    }

    /** GET /api/v1/interviews/mine (any staff role): interviews assigned to the caller, soonest first. */
    @Transactional(readOnly = true)
    public List<InterviewResponse> mine() {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        return views.staffViews(interviewRepository.findAssigned(caller.userId(), caller.organizationId()));
    }

    /**
     * GET /api/v1/interviews/{id}: the assigned interviewer, or ADMIN/HR_MANAGER of the organisation (others of the
     * organisation get 403; another organisation's interview is 404).
     */
    @Transactional(readOnly = true)
    public InterviewDetailResponse detail(Long interviewId) {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        Interview interview = findInOrganization(interviewId, caller);
        boolean assigned = Objects.equals(interview.getInterviewerUserId(), caller.userId());
        if (!assigned && !caller.hasAnyRole(Role.ADMIN, Role.HR_MANAGER)) {
            throw new ForbiddenException("Only the assigned interviewer or a hiring manager can open this interview");
        }
        Application application = interview.getApplication();
        Job job = application.getJob();
        List<InterviewQuestion> kit = kitRepository.findByJobId(job.getId())
                .map(found -> views.questions(found.getQuestionsJson())).orElse(List.of());
        return InterviewDetailResponse.of(views.staffView(interview), kit, focusRequirements(job, application));
    }

    /**
     * POST /api/v1/interviews/{id}/scorecard: the assigned interviewer only; SCHEDULED interviews only (a second
     * scorecard is 409). Completes the interview, audits SCORECARD_SUBMITTED and queues re-matching, so ratings of
     * 3 or 4 on requirement-linked questions become INTERVIEW_VERIFIED evidence.
     */
    public InterviewResponse submitScorecard(Long interviewId, ScorecardRequest request) {
        if (request == null || request.ratings() == null || request.recommendation() == null) {
            throw new BadRequestException("ratings and recommendation are required");
        }
        CurrentUser caller = securityUtils.requireOrganizationMember();
        lockInOrganization(interviewId, caller);
        Interview interview = findInOrganization(interviewId, caller);
        if (!Objects.equals(interview.getInterviewerUserId(), caller.userId())) {
            throw new ForbiddenException("Only the assigned interviewer can submit the scorecard");
        }
        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new BadRequestException("This interview was cancelled");
        }
        if (interview.getStatus() == InterviewStatus.COMPLETED || scorecardRepository.existsByInterviewId(interview.getId())) {
            throw new DuplicateResourceException("A scorecard was already submitted for this interview");
        }
        Application application = interview.getApplication();
        if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
            throw new BadRequestException("The candidate withdrew this application");
        }
        Job job = application.getJob();
        Set<Long> requirementIds = new HashSet<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderById(job.getId())) {
            requirementIds.add(requirement.getId());
        }
        Map<String, Long> kitLinks = new HashMap<>();
        kitRepository.findByJobId(job.getId()).ifPresent(kit -> {
            for (InterviewQuestion question : views.questions(kit.getQuestionsJson())) {
                if (question.id() != null) {
                    kitLinks.put(question.id(), question.requirementId());
                }
            }
        });
        List<ScorecardRating> ratings = validateRatings(request.ratings(), requirementIds, kitLinks);
        String summary = request.summary() == null || request.summary().isBlank() ? null : request.summary().trim();
        if (summary != null && summary.length() > MAX_SUMMARY) {
            throw new BadRequestException("The summary must be at most " + MAX_SUMMARY + " characters");
        }
        InterviewScorecard scorecard = new InterviewScorecard();
        scorecard.setInterviewId(interview.getId());
        scorecard.setRatingsJson(views.writeRatings(ratings));
        scorecard.setRecommendation(request.recommendation());
        scorecard.setSummary(summary);
        scorecard.setSubmittedAt(UtcTime.now());
        try {
            scorecardRepository.saveAndFlush(scorecard);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("A scorecard was already submitted for this interview");
        }
        interview.setStatus(InterviewStatus.COMPLETED);
        Interview saved = interviewRepository.saveAndFlush(interview);
        long verified = ratings.stream().filter(rating -> rating.requirementId() != null
                && rating.rating() >= VERIFIED_MIN_RATING).count();
        auditService.record(AuditActions.SCORECARD_SUBMITTED, "INTERVIEW", String.valueOf(saved.getId()),
                "Scorecard for interview #" + saved.getId() + " of application #" + application.getId() + " (job #"
                        + job.getId() + "): recommendation " + request.recommendation() + ", " + ratings.size()
                        + " ratings, " + verified + " requirement-linked ratings at 3 or above");
        matchTriggerService.requestMatch(application.getId());
        return views.staffView(saved);
    }

    /** Applications whose scorecards can no longer be edited (the hiring decision was made, or the candidate left). */
    public static final Set<ApplicationStatus> SCORECARD_LOCKED = EnumSet.of(ApplicationStatus.SELECTED,
            ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN);

    /**
     * PUT /api/v1/interviews/{id}/scorecard (WP-PLAT): the scorecard's author (the assigned interviewer who submitted
     * it) replaces ratings, recommendation and summary until the application is SELECTED, REJECTED or WITHDRAWN. The
     * replaced version is stored in {@code interview_scorecard_revisions}; audited as SCORECARD_UPDATED; re-matching is
     * queued as after a submission. {@code scorecardId} (alias route) must be the interview's scorecard when given.
     */
    public InterviewResponse updateScorecard(Long interviewId, Long scorecardId, ScorecardRequest request) {
        if (request == null || request.ratings() == null || request.recommendation() == null) {
            throw new BadRequestException("ratings and recommendation are required");
        }
        CurrentUser caller = securityUtils.requireOrganizationMember();
        lockInOrganization(interviewId, caller);
        Interview interview = findInOrganization(interviewId, caller);
        InterviewScorecard scorecard = scorecardRepository.findByInterviewId(interview.getId())
                .orElseThrow(() -> new ResourceNotFoundException("This interview has no scorecard yet"));
        if (scorecardId != null && !scorecardId.equals(scorecard.getId())) {
            throw new ResourceNotFoundException("Scorecard not found");
        }
        if (!Objects.equals(interview.getInterviewerUserId(), caller.userId())) {
            throw new ForbiddenException("Only the interviewer who submitted the scorecard can edit it");
        }
        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new BadRequestException("This interview was cancelled");
        }
        Application application = interview.getApplication();
        if (SCORECARD_LOCKED.contains(application.getStatus())) {
            throw new BadRequestException("The application has been decided, so the scorecard can no longer be changed");
        }
        Job job = application.getJob();
        Set<Long> requirementIds = new HashSet<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderById(job.getId())) {
            requirementIds.add(requirement.getId());
        }
        Map<String, Long> kitLinks = new HashMap<>();
        kitRepository.findByJobId(job.getId()).ifPresent(kit -> {
            for (InterviewQuestion question : views.questions(kit.getQuestionsJson())) {
                if (question.id() != null) {
                    kitLinks.put(question.id(), question.requirementId());
                }
            }
        });
        List<ScorecardRating> ratings = validateRatings(request.ratings(), requirementIds, kitLinks);
        String summary = request.summary() == null || request.summary().isBlank() ? null : request.summary().trim();
        if (summary != null && summary.length() > MAX_SUMMARY) {
            throw new BadRequestException("The summary must be at most " + MAX_SUMMARY + " characters");
        }
        ScorecardResponse previous = views.scorecard(scorecard, 0);
        if (previous.ratings().equals(ratings) && previous.recommendation() == request.recommendation()
                && Objects.equals(previous.summary(), summary)) {
            return views.staffView(interview);
        }
        InterviewScorecardRevision revision = new InterviewScorecardRevision();
        revision.setScorecardId(scorecard.getId());
        revision.setRevisedBy(caller.userId());
        revision.setSnapshotJson(JsonText.write(objectMapper, previous));
        revisionRepository.save(revision);
        String before = previous.recommendation() + ", " + previous.ratings().size() + " ratings";
        scorecard.setRatingsJson(views.writeRatings(ratings));
        scorecard.setRecommendation(request.recommendation());
        scorecard.setSummary(summary);
        scorecardRepository.saveAndFlush(scorecard);
        long verified = ratings.stream().filter(rating -> rating.requirementId() != null
                && rating.rating() >= VERIFIED_MIN_RATING).count();
        auditService.record(AuditActions.SCORECARD_UPDATED, "INTERVIEW", String.valueOf(interview.getId()),
                "Scorecard #" + scorecard.getId() + " of interview #" + interview.getId() + " (application #"
                        + application.getId() + ", job #" + job.getId() + ") edited by its author: was " + before
                        + "; now " + request.recommendation() + ", " + ratings.size() + " ratings, " + verified
                        + " requirement-linked ratings at 3 or above");
        matchTriggerService.requestMatch(application.getId());
        return views.staffView(interview);
    }

    /**
     * GET /api/v1/interviews/{id}/scorecard/revisions: earlier versions, newest first (the author, ADMIN or
     * HR_MANAGER of the organisation).
     */
    @Transactional(readOnly = true)
    public List<ScorecardRevisionResponse> scorecardRevisions(Long interviewId) {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        Interview interview = findInOrganization(interviewId, caller);
        boolean assigned = Objects.equals(interview.getInterviewerUserId(), caller.userId());
        if (!assigned && !caller.hasAnyRole(Role.ADMIN, Role.HR_MANAGER)) {
            throw new ForbiddenException("Only the interviewer or a hiring manager can see the scorecard history");
        }
        InterviewScorecard scorecard = scorecardRepository.findByInterviewId(interview.getId()).orElse(null);
        if (scorecard == null) {
            return List.of();
        }
        List<InterviewScorecardRevision> revisions = revisionRepository.findAllByScorecardIdOrderByIdDesc(scorecard.getId());
        Set<Long> editorIds = new HashSet<>();
        for (InterviewScorecardRevision revision : revisions) {
            if (revision.getRevisedBy() != null) {
                editorIds.add(revision.getRevisedBy());
            }
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(editorIds)) {
            names.put(user.getId(), user.getFullName());
        }
        List<ScorecardRevisionResponse> result = new ArrayList<>(revisions.size());
        for (InterviewScorecardRevision revision : revisions) {
            ScorecardResponse snapshot = views.readScorecardSnapshot(revision.getSnapshotJson());
            result.add(new ScorecardRevisionResponse(revision.getId(), revision.getRevisedBy(),
                    revision.getRevisedBy() == null ? null : names.getOrDefault(revision.getRevisedBy(), "Former staff member"),
                    revision.getCreatedAt(), snapshot));
        }
        return result;
    }

    // ---- internals ----

    /**
     * RequirementEvidence from the current match run, requirements without verified evidence first (knockouts,
     * then must-haves, then the rest), then verified ones from the weakest tier up.
     */
    List<RequirementEvidenceResponse> focusRequirements(Job job, Application application) {
        JobMatchRun run = matchRunRepository.findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(application.getId())
                .orElse(null);
        return focusOrder(applicantQueryService.requirementEvidence(job, run));
    }

    static List<RequirementEvidenceResponse> focusOrder(List<RequirementEvidenceResponse> evidence) {
        List<RequirementEvidenceResponse> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparingInt((RequirementEvidenceResponse item) -> isVerified(item) ? 1 : 0)
                .thenComparingInt(item -> item.knockout() ? 0 : 1)
                .thenComparingInt(item -> item.mandatory() ? 0 : 1)
                .thenComparingInt(item -> item.tier() == null ? -1 : item.tier().level())
                .thenComparing(RequirementEvidenceResponse::requirementId, Comparator.nullsLast(Comparator.<Long>naturalOrder())));
        return sorted;
    }

    static boolean isVerified(RequirementEvidenceResponse item) {
        return "MATCHED".equals(item.status()) && item.tier() != null && item.tier().isVerified();
    }

    /**
     * Validates the ratings and sets each rating's requirement link from the job's interview kit ({@code kitLinks}:
     * question id &rarr; requirement id or null), never from the request: an interviewer cannot point a rating at a
     * different requirement. A question that is not in the kit (any more) is stored without a link. A requirementId
     * sent by the client must still be null or a requirement of the job.
     */
    static List<ScorecardRating> validateRatings(List<ScorecardRating> submitted, Set<Long> requirementIds,
                                                 Map<String, Long> kitLinks) {
        if (submitted.size() > 100) {
            throw new BadRequestException("A scorecard can have at most 100 ratings");
        }
        Set<String> questionIds = new HashSet<>();
        List<ScorecardRating> ratings = new ArrayList<>(submitted.size());
        for (ScorecardRating rating : submitted) {
            if (rating == null || rating.questionId() == null || rating.questionId().isBlank()
                    || rating.questionId().trim().length() > MAX_QUESTION_ID) {
                throw new BadRequestException("Every rating needs a questionId of 1 to " + MAX_QUESTION_ID + " characters");
            }
            String questionId = rating.questionId().trim();
            if (!questionIds.add(questionId)) {
                throw new BadRequestException("Each question can be rated only once");
            }
            if (rating.rating() == null || rating.rating() < 1 || rating.rating() > 4) {
                throw new BadRequestException("Each rating must be between 1 and 4");
            }
            if (rating.requirementId() != null && !requirementIds.contains(rating.requirementId())) {
                throw new BadRequestException("A rating is linked to a requirement that is not part of this job");
            }
            String notes = rating.notes() == null || rating.notes().isBlank() ? null : rating.notes().trim();
            if (notes != null && notes.length() > MAX_NOTES) {
                throw new BadRequestException("Notes must be at most " + MAX_NOTES + " characters");
            }
            Long linked = kitLinks.get(questionId);
            ratings.add(new ScorecardRating(questionId, linked != null && requirementIds.contains(linked) ? linked : null,
                    rating.rating(), notes));
        }
        return ratings;
    }

    /**
     * Checks the organisation without loading the entity, then locks the row (plain SELECT … FOR UPDATE) so that the
     * status read afterwards is current. Missing or foreign rows are 404 and are never locked.
     */
    private void lockInOrganization(Long interviewId, CurrentUser caller) {
        Long organizationId = interviewId == null ? null : interviewRepository.findOrganizationIdById(interviewId).orElse(null);
        if (organizationId == null || !organizationId.equals(caller.organizationId())
                || interviewRepository.lockById(interviewId).isEmpty()) {
            throw new ResourceNotFoundException("Interview not found");
        }
    }

    /** The interview with application and job; 404 when missing or of another organisation (D3). */
    private Interview findInOrganization(Long interviewId, CurrentUser caller) {
        Interview interview = interviewRepository.findWithApplicationById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found"));
        if (!Objects.equals(interview.getApplication().getJob().getOrganizationId(), caller.organizationId())) {
            throw new ResourceNotFoundException("Interview not found");
        }
        return interview;
    }

    private User requireInterviewer(Long userId, Long organizationId) {
        User user = userRepository.findByIdAndOrganizationId(userId, organizationId).orElse(null);
        if (user == null || !user.isEnabled() || user.getDeletedAt() != null || user.getRole() == null
                || user.getRole() == Role.CANDIDATE) {
            throw new BadRequestException("The interviewer must be an enabled staff member of your organisation");
        }
        return user;
    }

    private static int requireDuration(Integer minutes) {
        if (minutes == null || minutes < MIN_DURATION || minutes > MAX_DURATION) {
            throw new BadRequestException("durationMinutes must be between " + MIN_DURATION + " and " + MAX_DURATION);
        }
        return minutes;
    }

    private static String location(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LOCATION) {
            throw new BadRequestException("location must be at most " + MAX_LOCATION + " characters");
        }
        return trimmed;
    }

    private void notifyScheduled(Interview interview, Application application, Job job, User interviewer) {
        String when = UtcTime.format(interview.getScheduledAt());
        User candidate = candidateUser(application);
        if (candidate != null) {
            Map<String, String> params = new HashMap<>();
            params.put("firstName", candidate.getFirstName() == null ? "" : candidate.getFirstName());
            params.put("jobTitle", job.getTitle());
            params.put("scheduledAt", when);
            params.put("durationMinutes", String.valueOf(interview.getDurationMinutes()));
            params.put("location", interview.getLocation() == null ? "to be confirmed" : interview.getLocation());
            NotificationAttachment invite = calendar.forCandidate(interview, application, job, candidate,
                    activeUser(interviewer) ? interviewer : null, IcsCalendar.Method.REQUEST);
            notificationService.notify(NotificationRecipient.of(candidate), NotificationTemplate.INTERVIEW_SCHEDULED, params, invite);
        }
        if (activeUser(interviewer)) {
            Map<String, String> params = new HashMap<>();
            params.put("firstName", interviewer.getFirstName() == null ? "" : interviewer.getFirstName());
            // E-mails never carry the candidate's identity; the portal shows it according to blind review.
            params.put("candidateLabel", ApplicantQueryService.blindName(application.getId()));
            params.put("jobTitle", job.getTitle());
            params.put("scheduledAt", when);
            String link = trimTrailingSlash(hrPortalUrl) + "/interviews/" + interview.getId();
            params.put("link", link);
            NotificationAttachment invite = calendar.forInterviewer(interview, application, job, candidate, interviewer,
                    link, IcsCalendar.Method.REQUEST);
            notificationService.notify(NotificationRecipient.of(interviewer), NotificationTemplate.INTERVIEW_ASSIGNED, params, invite);
        }
    }

    private void notifyCancelled(Interview interview, Application application, Job job, User interviewer) {
        String when = UtcTime.format(interview.getScheduledAt());
        User candidate = candidateUser(application);
        if (candidate != null) {
            Map<String, String> params = new HashMap<>();
            params.put("firstName", candidate.getFirstName() == null ? "" : candidate.getFirstName());
            params.put("jobTitle", job.getTitle());
            params.put("scheduledAt", when);
            params.put("message", "The hiring team will contact you if a new time is arranged.");
            NotificationAttachment cancel = calendar.forCandidate(interview, application, job, candidate,
                    activeUser(interviewer) ? interviewer : null, IcsCalendar.Method.CANCEL);
            notificationService.notify(NotificationRecipient.of(candidate), NotificationTemplate.INTERVIEW_CANCELLED, params, cancel);
        }
        if (activeUser(interviewer)) {
            Map<String, String> params = new HashMap<>();
            params.put("firstName", interviewer.getFirstName() == null ? "" : interviewer.getFirstName());
            params.put("jobTitle", job.getTitle());
            params.put("scheduledAt", when);
            params.put("message", "You no longer need to interview " + ApplicantQueryService.blindName(application.getId()) + ".");
            NotificationAttachment cancel = calendar.forInterviewer(interview, application, job, candidate, interviewer,
                    trimTrailingSlash(hrPortalUrl) + "/interviews/" + interview.getId(), IcsCalendar.Method.CANCEL);
            notificationService.notify(NotificationRecipient.of(interviewer), NotificationTemplate.INTERVIEW_CANCELLED, params, cancel);
        }
    }

    private static User candidateUser(Application application) {
        User user = application.getCandidate() == null ? null : application.getCandidate().getUser();
        return activeUser(user) ? user : null;
    }

    private static boolean activeUser(User user) {
        return user != null && user.isEnabled() && user.getDeletedAt() == null && user.getEmail() != null
                && !user.getEmail().isBlank();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
