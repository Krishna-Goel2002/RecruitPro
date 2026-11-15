package com.recruitpro.assessment.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.dto.AiWorkSampleRequest;
import com.recruitpro.ai.dto.AiWorkSampleResponse;
import com.recruitpro.ai.service.AiAuthoringCalls;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.dto.AssignWorkSampleRequest;
import com.recruitpro.assessment.dto.CancelAssignmentRequest;
import com.recruitpro.assessment.dto.CriterionScore;
import com.recruitpro.assessment.dto.RubricCriterion;
import com.recruitpro.assessment.dto.ScoreRequest;
import com.recruitpro.assessment.dto.WorkSampleAssignmentResponse;
import com.recruitpro.assessment.dto.WorkSampleResponse;
import com.recruitpro.assessment.dto.WorkSampleStatusRequest;
import com.recruitpro.assessment.dto.WorkSampleUpdateRequest;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.AnchorLevels;
import com.recruitpro.evidence.service.MatchRequestBuilder;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Work samples, staff side (plan 5.6). Generation calls the AI service outside any transaction (like requirement
 * compiling) and stores the draft afterwards. Assignment needs a SHORTLISTED or INTERVIEWING application and an
 * ACTIVE sample; scoring needs a SUBMITTED assignment, is audited (WORK_SAMPLE_SCORED) and queues re-matching, so
 * requirement-linked criteria scored 3 or 4 become DEMONSTRATED evidence ({@link WorkSampleEvidenceContributor}).
 */
@Service
public class WorkSampleService {

    static final int MAX_RUBRIC = 30;
    static final int MAX_CRITERION = 500;
    static final int MAX_ANCHOR = 1000;
    static final int MAX_NOTE = 2000;
    static final int MIN_TIME_LIMIT = 1;
    static final int MAX_TIME_LIMIT = 480;
    static final int DEFAULT_TIME_LIMIT = 30;
    static final String MISSING_ANCHOR = "Not described yet";
    /** Applications that may receive a work sample. */
    public static final Set<ApplicationStatus> ASSIGNABLE = EnumSet.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING);

    private static final String UNAVAILABLE = "Work-sample drafting is temporarily unavailable; please try again later";
    private static final String REJECTED = "The job could not be turned into a work sample; check its title, description and requirements";

    private final HrJobAccessService jobAccess;
    private final SecurityUtils securityUtils;
    private final WorkSampleRepository workSampleRepository;
    private final WorkSampleAssignmentRepository assignmentRepository;
    private final JobRepository jobRepository;
    private final JobRequirementRepository requirementRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;
    private final NotificationService notificationService;
    private final AiServiceClient aiServiceClient;
    private final WorkSampleViews views;
    private final TransactionTemplate transactionTemplate;
    private final WorkSampleFileService fileService;
    private final String candidatePortalUrl;

    public WorkSampleService(HrJobAccessService jobAccess, SecurityUtils securityUtils,
                             WorkSampleRepository workSampleRepository, WorkSampleAssignmentRepository assignmentRepository,
                             JobRepository jobRepository, JobRequirementRepository requirementRepository,
                             ApplicationRepository applicationRepository, AuditService auditService, MatchTriggerService matchTriggerService,
                             NotificationService notificationService, AiServiceClient aiServiceClient,
                             WorkSampleViews views, PlatformTransactionManager transactionManager,
                             WorkSampleFileService fileService,
                             @Value("${app.frontend.candidate-url:http://localhost:5173}") String candidatePortalUrl) {
        this.jobAccess = jobAccess;
        this.securityUtils = securityUtils;
        this.workSampleRepository = workSampleRepository;
        this.assignmentRepository = assignmentRepository;
        this.jobRepository = jobRepository;
        this.requirementRepository = requirementRepository;
        this.applicationRepository = applicationRepository;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
        this.notificationService = notificationService;
        this.aiServiceClient = aiServiceClient;
        this.views = views;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.fileService = fileService;
        this.candidatePortalUrl = candidatePortalUrl;
    }

    /**
     * POST /api/v1/hr/jobs/{jobId}/work-samples/generate (ADMIN, HR_MANAGER): drafts a sample with the AI service and
     * stores it as DRAFT. Not transactional: no database connection is held during the AI call. 503 when the AI
     * service is unavailable, 400 when it rejects the input.
     */
    public WorkSampleResponse generate(Long jobId) {
        Job job = jobAccess.requireManageAccess(jobId);
        // Closed jobs may still have applicants in progress; only cancelled jobs (never published) are excluded.
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new BadRequestException("Cancelled jobs cannot get work samples");
        }
        CurrentUser actor = securityUtils.currentUser();
        List<JobRequirement> requirements = requirementRepository.findByJobIdOrderById(job.getId());
        List<AiMatchRequest.Requirement> aiRequirements = new ArrayList<>();
        Set<Long> requirementIds = new HashSet<>();
        for (JobRequirement requirement : requirements) {
            requirementIds.add(requirement.getId());
            if (aiRequirements.size() < AiMatchRequest.MAX_REQUIREMENTS) {
                aiRequirements.add(MatchRequestBuilder.requirement(requirement));
            }
        }
        AiWorkSampleRequest request = new AiWorkSampleRequest(limit(job.getTitle(), 200), limit(job.getDescription(), 20000),
                List.copyOf(aiRequirements));
        AiWorkSampleResponse response = AiAuthoringCalls.call(() -> aiServiceClient.generateWorkSample(request),
                UNAVAILABLE, REJECTED);
        if (response.instructions() == null || response.instructions().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
        List<RubricCriterion> rubric = new ArrayList<>();
        for (AiWorkSampleResponse.Criterion criterion : response.rubric() == null
                ? List.<AiWorkSampleResponse.Criterion>of() : response.rubric()) {
            if (criterion == null || criterion.criterion() == null || criterion.criterion().isBlank()) {
                continue;
            }
            if (rubric.size() >= MAX_RUBRIC) {
                break;
            }
            Long requirementId = criterion.requirementId() != null && requirementIds.contains(criterion.requirementId())
                    ? criterion.requirementId() : null;
            rubric.add(new RubricCriterion(requirementId, limit(criterion.criterion(), MAX_CRITERION),
                    AnchorLevels.normalize(criterion.anchors(), MISSING_ANCHOR, MAX_ANCHOR)));
        }
        String title = response.title() == null || response.title().isBlank()
                ? limit(job.getTitle() + " work sample", 255) : limit(response.title(), 255);
        int timeLimit = response.timeLimitMinutes() == null ? DEFAULT_TIME_LIMIT
                : Math.max(MIN_TIME_LIMIT, Math.min(MAX_TIME_LIMIT, response.timeLimitMinutes()));
        String instructions = limit(response.instructions(), 20000);
        Long jobIdValue = job.getId();
        return transactionTemplate.execute(status -> {
            WorkSample sample = new WorkSample();
            sample.setJob(jobRepository.getReferenceById(jobIdValue));
            sample.setTitle(title);
            sample.setInstructions(instructions);
            sample.setTimeLimitMinutes(timeLimit);
            sample.setRubricJson(views.writeRubric(rubric));
            sample.setStatus(WorkSampleStatus.DRAFT);
            sample.setCreatedByUserId(actor.userId());
            WorkSample saved = workSampleRepository.saveAndFlush(sample);
            return new WorkSampleResponse(saved.getId(), jobIdValue, saved.getTitle(), saved.getInstructions(),
                    saved.getTimeLimitMinutes(), views.rubric(saved.getRubricJson()), saved.getStatus(),
                    saved.getCreatedAt(), saved.getUpdatedAt());
        });
    }

    /** GET /api/v1/hr/jobs/{jobId}/work-samples (ADMIN, HR_MANAGER, RECRUITER). */
    @Transactional(readOnly = true)
    public List<WorkSampleResponse> list(Long jobId) {
        Job job = jobAccess.requireJobReadAccess(jobId);
        return workSampleRepository.findAllByJobIdOrderByIdAsc(job.getId()).stream().map(views::workSample).toList();
    }

    /** PUT /api/v1/hr/jobs/{jobId}/work-samples/{id} (ADMIN, HR_MANAGER): DRAFT samples only. */
    @Transactional
    public WorkSampleResponse update(Long jobId, Long workSampleId, WorkSampleUpdateRequest request) {
        Job job = jobAccess.requireManageAccess(jobId);
        WorkSample sample = findSample(job, workSampleId);
        if (sample.getStatus() != WorkSampleStatus.DRAFT) {
            throw new BadRequestException("Only draft work samples can be edited; move it back to draft first "
                    + "(allowed while nobody has been assigned it)");
        }
        List<RubricCriterion> rubric = validateRubric(request.rubric(), requirementIds(job));
        sample.setTitle(request.title().trim());
        sample.setInstructions(request.instructions().trim());
        sample.setTimeLimitMinutes(request.timeLimitMinutes());
        sample.setRubricJson(views.writeRubric(rubric));
        return views.workSample(workSampleRepository.saveAndFlush(sample));
    }

    /**
     * PATCH …/work-samples/{id}/status (ADMIN, HR_MANAGER). DRAFT &rarr; ACTIVE or ARCHIVED; ACTIVE &rarr; ARCHIVED;
     * ARCHIVED &rarr; ACTIVE. Back to DRAFT only while the sample has no assignments. Activation needs instructions and
     * a complete rubric. The same status is a no-op.
     */
    @Transactional
    public WorkSampleResponse changeStatus(Long jobId, Long workSampleId, WorkSampleStatusRequest request) {
        if (request == null || request.status() == null) {
            throw new BadRequestException("status is required");
        }
        Job job = jobAccess.requireManageAccess(jobId);
        WorkSample sample = findSample(job, workSampleId);
        WorkSampleStatus target = request.status();
        if (sample.getStatus() == target) {
            return views.workSample(sample);
        }
        if (target == WorkSampleStatus.DRAFT && assignmentRepository.existsByWorkSampleId(sample.getId())) {
            throw new BadRequestException("This work sample has already been assigned and can no longer return to draft");
        }
        if (target == WorkSampleStatus.ACTIVE) {
            if (sample.getInstructions() == null || sample.getInstructions().isBlank()) {
                throw new BadRequestException("Add instructions before activating the work sample");
            }
            // Requirement links are checked when a draft goes live; a re-activated sample keeps the links it was used with.
            validateRubric(views.rubric(sample.getRubricJson()),
                    sample.getStatus() == WorkSampleStatus.DRAFT ? requirementIds(job) : null);
        }
        sample.setStatus(target);
        return views.workSample(workSampleRepository.saveAndFlush(sample));
    }

    /**
     * POST /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/work-samples (ADMIN, HR_MANAGER). The application row is
     * locked like a decision. Notifies the candidate (WORK_SAMPLE_ASSIGNED).
     */
    @Transactional
    public WorkSampleAssignmentResponse assign(Long jobId, Long applicationId, AssignWorkSampleRequest request) {
        if (request == null || request.workSampleId() == null) {
            throw new BadRequestException("workSampleId is required");
        }
        Job job = jobAccess.requireManageAccess(jobId);
        Application application = applicationRepository.findForReview(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (!ASSIGNABLE.contains(application.getStatus())) {
            throw new BadRequestException("Work samples can be assigned to shortlisted or interviewing applications only");
        }
        WorkSample sample = workSampleRepository.findByIdAndJobId(request.workSampleId(), job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Work sample not found"));
        if (sample.getStatus() != WorkSampleStatus.ACTIVE) {
            throw new BadRequestException("Only active work samples can be assigned");
        }
        LocalDateTime now = UtcTime.now();
        if (request.dueAt() != null && !request.dueAt().isAfter(now)) {
            throw new BadRequestException("dueAt must be in the future");
        }
        if (assignmentRepository.existsByWorkSampleIdAndApplicationId(sample.getId(), application.getId())) {
            throw new DuplicateResourceException("This work sample is already assigned to the application");
        }
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(sample);
        assignment.setApplication(application);
        assignment.setStatus(WorkSampleAssignmentStatus.ASSIGNED);
        assignment.setAssignedAt(now);
        assignment.setDueAt(request.dueAt());
        WorkSampleAssignment saved;
        try {
            saved = assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("This work sample is already assigned to the application");
        }
        notifyCandidate(application, job, sample, request.dueAt());
        return views.assignment(saved);
    }

    /** GET /api/v1/hr/jobs/{jobId}/work-sample-assignments?status (ADMIN, HR_MANAGER, RECRUITER), newest first. */
    @Transactional(readOnly = true)
    public List<WorkSampleAssignmentResponse> listAssignments(Long jobId, WorkSampleAssignmentStatus status) {
        Job job = jobAccess.requireJobReadAccess(jobId);
        List<WorkSampleAssignment> assignments = status == null
                ? assignmentRepository.findAllForJob(job.getId())
                : assignmentRepository.findAllForJobAndStatus(job.getId(), status);
        return views.assignments(assignments);
    }

    /**
     * POST /api/v1/hr/work-sample-assignments/{id}/score (ADMIN, HR_MANAGER, RECRUITER): SUBMITTED assignments only;
     * one score 1-4 per rubric criterion. Audited (WORK_SAMPLE_SCORED); queues MATCH_APPLICATION.
     */
    @Transactional
    public WorkSampleAssignmentResponse score(Long assignmentId, ScoreRequest request) {
        if (request == null || request.scores() == null || request.scores().isEmpty()) {
            throw new BadRequestException("scores are required");
        }
        Long jobId = assignmentRepository.findJobIdById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Work sample assignment not found"));
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        WorkSampleAssignment assignment = assignmentRepository.lockById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Work sample assignment not found"));
        if (assignment.getStatus() != WorkSampleAssignmentStatus.SUBMITTED) {
            throw new BadRequestException("Only submitted work samples can be scored");
        }
        List<RubricCriterion> rubric = views.rubric(assignment.getWorkSample().getRubricJson());
        List<CriterionScore> scores = matchScores(rubric, request.scores());
        int sum = 0;
        for (CriterionScore score : scores) {
            sum += score.score();
        }
        BigDecimal mean = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
        CurrentUser actor = securityUtils.currentUser();
        assignment.setScoresJson(views.writeScores(scores));
        assignment.setTotalScore(mean);
        assignment.setScoredByUserId(actor.userId());
        assignment.setScoredAt(UtcTime.now());
        assignment.setStatus(WorkSampleAssignmentStatus.SCORED);
        WorkSampleAssignment saved = assignmentRepository.saveAndFlush(assignment);
        long linked = scores.stream().filter(score -> score.requirementId() != null
                && score.score() >= WorkSampleEvidenceContributor.DEMONSTRATED_MIN_SCORE).count();
        Long applicationId = saved.getApplication().getId();
        auditService.record(AuditActions.WORK_SAMPLE_SCORED, "WORK_SAMPLE_ASSIGNMENT", String.valueOf(saved.getId()),
                "Work sample assignment #" + saved.getId() + " of application #" + applicationId + " (job #" + job.getId()
                        + ") scored: mean " + mean.toPlainString() + " over " + scores.size() + " criteria, "
                        + linked + " requirement-linked criteria at 3 or above");
        matchTriggerService.requestMatch(applicationId);
        return views.assignment(saved);
    }

    /** Assignments HR may cancel (anything not finished by expiry or an earlier cancellation). */
    public static final Set<WorkSampleAssignmentStatus> CANCELLABLE = EnumSet.of(WorkSampleAssignmentStatus.ASSIGNED,
            WorkSampleAssignmentStatus.STARTED, WorkSampleAssignmentStatus.SUBMITTED, WorkSampleAssignmentStatus.SCORED);

    /**
     * POST /api/v1/hr/work-sample-assignments/{id}/cancel (ADMIN, HR_MANAGER; WP-PLAT): status CANCELLED with the
     * reason, time and user. A SCORED assignment stops counting as DEMONSTRATED evidence, so re-matching is queued.
     * The candidate is notified (WORK_SAMPLE_CANCELLED, with the reason); audited as WORK_SAMPLE_CANCELLED.
     */
    @Transactional
    public WorkSampleAssignmentResponse cancel(Long assignmentId, CancelAssignmentRequest request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 1000) {
            throw new BadRequestException("A reason of 1 to 1000 characters is required");
        }
        Long jobId = assignmentRepository.findJobIdById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Work sample assignment not found"));
        Job job = jobAccess.requireManageAccess(jobId);
        WorkSampleAssignment assignment = assignmentRepository.lockById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Work sample assignment not found"));
        if (!CANCELLABLE.contains(assignment.getStatus())) {
            throw new BadRequestException(assignment.getStatus() == WorkSampleAssignmentStatus.CANCELLED
                    ? "This work sample assignment is already cancelled"
                    : "Expired work sample assignments cannot be cancelled");
        }
        WorkSampleAssignmentStatus previous = assignment.getStatus();
        CurrentUser actor = securityUtils.currentUser();
        assignment.setStatus(WorkSampleAssignmentStatus.CANCELLED);
        assignment.setCancelledAt(UtcTime.now());
        assignment.setCancelledByUserId(actor.userId());
        assignment.setCancelReason(reason);
        WorkSampleAssignment saved = assignmentRepository.saveAndFlush(assignment);
        Application application = saved.getApplication();
        WorkSample sample = saved.getWorkSample();
        auditService.record(AuditActions.WORK_SAMPLE_CANCELLED, "WORK_SAMPLE_ASSIGNMENT", String.valueOf(saved.getId()),
                "Work sample assignment #" + saved.getId() + " of application #" + application.getId() + " (job #"
                        + job.getId() + ") cancelled (was " + previous + "): " + limit(reason, 300));
        if (previous == WorkSampleAssignmentStatus.SCORED) {
            matchTriggerService.requestMatch(application.getId());
        }
        User user = application.getCandidate() == null ? null : application.getCandidate().getUser();
        if (user != null && user.getDeletedAt() == null && user.isEnabled() && user.getEmail() != null) {
            Map<String, String> params = new HashMap<>();
            params.put("firstName", user.getFirstName() == null ? "" : user.getFirstName());
            params.put("jobTitle", job.getTitle());
            params.put("title", sample.getTitle());
            params.put("reason", reason);
            notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.WORK_SAMPLE_CANCELLED, params);
        }
        return views.assignment(saved);
    }

    /**
     * GET /api/v1/hr/work-sample-assignments/{id}/files/{fileId} (ADMIN, HR_MANAGER, RECRUITER of the job's
     * organisation): the submitted file, audited as WORK_SAMPLE_FILE_DOWNLOADED.
     */
    @Transactional
    public WorkSampleFileService.Download downloadFile(Long assignmentId, Long fileId) {
        Long jobId = assignmentRepository.findJobIdById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
        Job job = jobAccess.requireApplicantReadAccess(jobId);
        WorkSampleFileService.Download download = fileService.load(assignmentId, fileId);
        auditService.record(AuditActions.WORK_SAMPLE_FILE_DOWNLOADED, "WORK_SAMPLE_ASSIGNMENT", String.valueOf(assignmentId),
                "Downloaded file #" + download.file().getId() + " (" + download.file().getContentType() + ", "
                        + download.file().getSizeBytes() + " bytes) of work sample assignment #" + assignmentId
                        + " (job #" + job.getId() + ")");
        return download;
    }

    // ---- internals ----

    /**
     * Pairs every rubric criterion with exactly one submitted score (matched by criterion text, case-insensitive).
     * The requirement link comes from the stored rubric. Scores must be 1-4; notes at most 2000 characters.
     */
    static List<CriterionScore> matchScores(List<RubricCriterion> rubric, List<CriterionScore> submitted) {
        if (rubric.isEmpty()) {
            throw new BadRequestException("This work sample has no rubric to score against");
        }
        if (submitted.size() != rubric.size()) {
            throw new BadRequestException("Score every rubric criterion exactly once");
        }
        boolean[] used = new boolean[submitted.size()];
        List<CriterionScore> result = new ArrayList<>(rubric.size());
        for (RubricCriterion criterion : rubric) {
            int index = -1;
            for (int i = 0; i < submitted.size(); i++) {
                CriterionScore candidate = submitted.get(i);
                if (!used[i] && candidate != null && candidate.criterion() != null && criterion.criterion() != null
                        && candidate.criterion().trim().equalsIgnoreCase(criterion.criterion().trim())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new BadRequestException("Missing a score for the criterion '" + limit(criterion.criterion(), 80) + "'");
            }
            used[index] = true;
            CriterionScore score = submitted.get(index);
            if (score.score() == null || score.score() < 1 || score.score() > 4) {
                throw new BadRequestException("Each score must be between 1 and 4");
            }
            String note = score.note() == null || score.note().isBlank() ? null : score.note().trim();
            if (note != null && note.length() > MAX_NOTE) {
                throw new BadRequestException("Notes must be at most " + MAX_NOTE + " characters");
            }
            result.add(new CriterionScore(criterion.requirementId(), criterion.criterion(), score.score(), note));
        }
        return result;
    }

    /**
     * Strict rubric validation for edits and activation; returns the normalised rubric. {@code requirementIds} null
     * skips the requirement-link check.
     */
    static List<RubricCriterion> validateRubric(List<RubricCriterion> rubric, Set<Long> requirementIds) {
        if (rubric == null || rubric.isEmpty()) {
            throw new BadRequestException("The rubric needs at least one criterion");
        }
        if (rubric.size() > MAX_RUBRIC) {
            throw new BadRequestException("The rubric can have at most " + MAX_RUBRIC + " criteria");
        }
        List<RubricCriterion> result = new ArrayList<>(rubric.size());
        for (RubricCriterion criterion : rubric) {
            if (criterion == null || criterion.criterion() == null || criterion.criterion().isBlank()
                    || criterion.criterion().trim().length() > MAX_CRITERION) {
                throw new BadRequestException("Every rubric criterion needs a name of 1 to " + MAX_CRITERION + " characters");
            }
            if (!AnchorLevels.complete(criterion.anchors(), MAX_ANCHOR)
                    || MISSING_ANCHOR.equals(criterion.anchors().get("1")) || MISSING_ANCHOR.equals(criterion.anchors().get("2"))
                    || MISSING_ANCHOR.equals(criterion.anchors().get("3")) || MISSING_ANCHOR.equals(criterion.anchors().get("4"))) {
                throw new BadRequestException("Describe all four anchors (1-4, at most " + MAX_ANCHOR
                        + " characters each) of '" + limit(criterion.criterion(), 80) + "'");
            }
            if (requirementIds != null && criterion.requirementId() != null && !requirementIds.contains(criterion.requirementId())) {
                throw new BadRequestException("A rubric criterion is linked to a requirement that is not part of this job");
            }
            result.add(new RubricCriterion(criterion.requirementId(), criterion.criterion().trim(),
                    AnchorLevels.normalize(criterion.anchors(), null, MAX_ANCHOR)));
        }
        return result;
    }

    private WorkSample findSample(Job job, Long workSampleId) {
        return workSampleRepository.findByIdAndJobId(workSampleId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Work sample not found"));
    }

    private Set<Long> requirementIds(Job job) {
        Set<Long> ids = new HashSet<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderById(job.getId())) {
            ids.add(requirement.getId());
        }
        return ids;
    }

    private void notifyCandidate(Application application, Job job, WorkSample sample, LocalDateTime dueAt) {
        User user = application.getCandidate() == null ? null : application.getCandidate().getUser();
        if (user == null || user.getDeletedAt() != null || !user.isEnabled() || user.getEmail() == null) {
            return;
        }
        Map<String, String> params = new HashMap<>();
        params.put("firstName", user.getFirstName() == null ? "" : user.getFirstName());
        params.put("jobTitle", job.getTitle());
        params.put("title", sample.getTitle());
        params.put("dueAt", dueAt == null ? "no due date; start whenever you are ready" : UtcTime.format(dueAt) + " (UTC)");
        params.put("link", trimTrailingSlash(candidatePortalUrl) + "/work-samples");
        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.WORK_SAMPLE_ASSIGNED, params);
    }

    static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
