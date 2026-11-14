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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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

    public CandidateWorkSampleService(CandidateProfileService profileService,
                                      WorkSampleAssignmentRepository assignmentRepository, WorkSampleViews views) {
        this.profileService = profileService;
        this.assignmentRepository = assignmentRepository;
        this.views = views;
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
        if (request == null || request.responseText() == null || request.responseText().isBlank()) {
            throw new BadRequestException("responseText is required");
        }
        WorkSampleAssignment assignment = lockOwned(assignmentId);
        if (assignment.getStatus() == WorkSampleAssignmentStatus.SUBMITTED
                || assignment.getStatus() == WorkSampleAssignmentStatus.SCORED) {
            throw new BadRequestException("This work sample has already been submitted");
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
        String url = request.responseUrl() == null || request.responseUrl().isBlank() ? null : request.responseUrl().trim();
        assignment.setResponseText(request.responseText().trim());
        assignment.setResponseUrl(url);
        assignment.setSubmittedAt(now);
        assignment.setStatus(WorkSampleAssignmentStatus.SUBMITTED);
        return views.candidateView(assignmentRepository.saveAndFlush(assignment));
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
