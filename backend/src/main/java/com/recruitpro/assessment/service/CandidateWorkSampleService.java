package com.recruitpro.assessment.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.assessment.dto.CandidateWorkSampleResponse;
import com.recruitpro.assessment.dto.WorkSampleSubmitRequest;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Work samples, candidate side (plan 5.5 and 5.6). Instructions stay hidden until the candidate presses Start, which
 * starts the timer; a submission is accepted until {@code startedAt + timeLimit + 5 minutes}. Starting is possible
 * until {@code dueAt}. Both need the application to still be SHORTLISTED or INTERVIEWING. Not read-only: the
 * candidate profile may be provisioned on first use.
 */
@Service
@Transactional
public class CandidateWorkSampleService {

    /** Grace period after the time limit for network delays and slow uploads. */
    public static final Duration GRACE = Duration.ofMinutes(5);

    private final CandidateProfileService profileService;
    private final WorkSampleAssignmentRepository assignmentRepository;
    private final WorkSampleViews views;
    private final WorkSampleFileService fileService;
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+");
    static final int MAX_RESPONSE_TEXT = 50000;
    static final int MAX_RESPONSE_URL = 500;

    public CandidateWorkSampleService(CandidateProfileService profileService,
                                      WorkSampleAssignmentRepository assignmentRepository, WorkSampleViews views,
                                      WorkSampleFileService fileService) {
        this.profileService = profileService;
        this.assignmentRepository = assignmentRepository;
        this.views = views;
        this.fileService = fileService;
    }

    /** GET /api/v1/candidates/me/work-samples: every assignment of the candidate, newest first. */
    public List<CandidateWorkSampleResponse> listMine() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return assignmentRepository.findAllForCandidate(candidate.getId()).stream().map(views::candidateView).toList();
    }

    /** POST …/{assignmentId}/start: ASSIGNED &rarr; STARTED (idempotent once started); reveals the instructions. */
    public CandidateWorkSampleResponse start(Long assignmentId) {
        WorkSampleAssignment assignment = lockOwned(assignmentId);
        if (assignment.getStatus() == WorkSampleAssignmentStatus.STARTED) {
            return views.candidateView(assignment);
        }
        if (assignment.getStatus() != WorkSampleAssignmentStatus.ASSIGNED) {
            throw new BadRequestException("This work sample can no longer be started");
        }
        LocalDateTime now = UtcTime.now();
        if (assignment.getDueAt() != null && now.isAfter(assignment.getDueAt())) {
            throw new BadRequestException("The due date for starting this work sample has passed");
        }
        requireActiveApplication(assignment.getApplication());
        assignment.setStatus(WorkSampleAssignmentStatus.STARTED);
        assignment.setStartedAt(now);
        return views.candidateView(assignmentRepository.saveAndFlush(assignment));
    }

    /**
     * POST …/{assignmentId}/submit: STARTED &rarr; SUBMITTED within the time limit plus the grace period. A late
     * submission marks the assignment EXPIRED (committed) and answers 400.
     */
    @Transactional(noRollbackFor = WorkSampleExpiredException.class)
    public CandidateWorkSampleResponse submit(Long assignmentId, WorkSampleSubmitRequest request) {
        if (request == null) {
            throw new BadRequestException("responseText is required");
        }
        return submit(assignmentId, request.responseText(), request.responseUrl(), List.of());
    }

    /**
     * Multipart submission (WP-PLAT): {@code responseText} (required unless at least one file is attached),
     * {@code responseUrl} and up to three {@code files} (pdf, zip, docx, pptx, png, jpg; 20 MB each). Files are
     * validated before anything changes; on an EXPIRED outcome nothing is stored.
     */
    @Transactional(noRollbackFor = WorkSampleExpiredException.class)
    public CandidateWorkSampleResponse submit(Long assignmentId, String responseText, String responseUrl,
                                              List<MultipartFile> files) {
        List<WorkSampleFileService.ValidatedFile> validated = fileService.validate(files);
        String text = responseText == null || responseText.isBlank() ? null : responseText.trim();
        if (text == null && validated.isEmpty()) {
            throw new BadRequestException("responseText is required");
        }
        if (text != null && text.length() > MAX_RESPONSE_TEXT) {
            throw new BadRequestException("responseText must be at most " + MAX_RESPONSE_TEXT + " characters");
        }
        String url = responseUrl == null || responseUrl.isBlank() ? null : responseUrl.trim();
        if (url != null && (url.length() > MAX_RESPONSE_URL || !HTTP_URL.matcher(url).matches())) {
            throw new BadRequestException("responseUrl must be an http or https URL of at most " + MAX_RESPONSE_URL + " characters");
        }
        WorkSampleAssignment assignment = lockOwned(assignmentId);
        if (assignment.getStatus() == WorkSampleAssignmentStatus.SUBMITTED
                || assignment.getStatus() == WorkSampleAssignmentStatus.SCORED) {
            throw new BadRequestException("This work sample has already been submitted");
        }
        if (assignment.getStatus() == WorkSampleAssignmentStatus.CANCELLED) {
            throw new BadRequestException("This work sample was cancelled by the hiring team");
        }
        if (assignment.getStatus() != WorkSampleAssignmentStatus.STARTED) {
            throw new BadRequestException("Start the work sample before submitting it");
        }
        requireActiveApplication(assignment.getApplication());
        LocalDateTime now = UtcTime.now();
        if (now.isAfter(submissionDeadline(assignment))) {
            assignment.setStatus(WorkSampleAssignmentStatus.EXPIRED);
            assignmentRepository.saveAndFlush(assignment);
            throw new WorkSampleExpiredException();
        }
        assignment.setResponseText(text);
        assignment.setResponseUrl(url);
        assignment.setSubmittedAt(now);
        assignment.setStatus(WorkSampleAssignmentStatus.SUBMITTED);
        WorkSampleAssignment saved = assignmentRepository.saveAndFlush(assignment);
        fileService.store(saved.getId(), validated);
        return views.candidateView(saved);
    }

    /** GET /api/v1/candidates/me/work-samples/{assignmentId}/files/{fileId}: one of the candidate's own files. */
    public WorkSampleFileService.Download downloadMine(Long assignmentId, Long fileId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Long ownerId = assignmentId == null ? null : assignmentRepository.findCandidateIdById(assignmentId).orElse(null);
        if (ownerId == null || !Objects.equals(ownerId, candidate.getId())) {
            throw new ResourceNotFoundException("File not found");
        }
        return fileService.load(assignmentId, fileId);
    }

    /** Last moment a submission is accepted: startedAt + time limit + grace. */
    public static LocalDateTime submissionDeadline(WorkSampleAssignment assignment) {
        return assignment.getStartedAt().plusMinutes(assignment.getWorkSample().getTimeLimitMinutes()).plus(GRACE);
    }

    /** Checks ownership without loading the entity, then locks the row; another candidate's assignment is 404. */
    private WorkSampleAssignment lockOwned(Long assignmentId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Long ownerId = assignmentId == null ? null : assignmentRepository.findCandidateIdById(assignmentId).orElse(null);
        if (ownerId == null || !Objects.equals(ownerId, candidate.getId())) {
            throw new ResourceNotFoundException("Work sample not found");
        }
        return assignmentRepository.lockById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Work sample not found"));
    }

    private static void requireActiveApplication(Application application) {
        if (!WorkSampleService.ASSIGNABLE.contains(application.getStatus())) {
            throw new BadRequestException("This application is no longer active, so the work sample is closed");
        }
    }
}
