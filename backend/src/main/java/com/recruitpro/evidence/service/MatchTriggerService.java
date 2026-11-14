package com.recruitpro.evidence.service;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Queues MATCH_APPLICATION jobs (D7). Jobs join the caller's transaction, so they exist exactly when the
 * triggering change commits. A trigger that arrives while a match for the application is running queues one
 * follow-up run ({@link BackgroundJobService#enqueueFollowUp}).
 *
 * <p>Triggers: application submitted, resume analysis finished (every application using the resume), claim
 * added, changed, deleted or verified, requirements changed on a published/on-hold job, the rematch endpoint,
 * and evidence contributors (WP-BE-C).</p>
 */
@Service
public class MatchTriggerService {

    /** Applications that are (re)matched; WITHDRAWN and SELECTED (terminal hand-off) are not. */
    public static final Set<ApplicationStatus> MATCHABLE = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING, ApplicationStatus.REJECTED);

    private final BackgroundJobService backgroundJobService;
    private final ApplicationRepository applicationRepository;

    public MatchTriggerService(BackgroundJobService backgroundJobService, ApplicationRepository applicationRepository) {
        this.backgroundJobService = backgroundJobService;
        this.applicationRepository = applicationRepository;
    }

    /** Queues a match for one application; false when one is already waiting. */
    @Transactional
    public boolean requestMatch(long applicationId) {
        return backgroundJobService.enqueueFollowUp(BackgroundJobType.MATCH_APPLICATION, applicationId);
    }

    /** Every matchable application of the job; returns how many applications are now waiting for a match. */
    @Transactional
    public int requestMatchForJob(long jobId) {
        return requestAll(applicationRepository.findIdsByJobIdAndStatusIn(jobId, MATCHABLE));
    }

    /** Every matchable application submitted with the resume (analysis finished). */
    @Transactional
    public int requestMatchForResume(long resumeId) {
        return requestAll(applicationRepository.findIdsByResumeIdAndStatusIn(resumeId, MATCHABLE));
    }

    /** Every matchable application of the candidate (claims are candidate-wide). */
    @Transactional
    public int requestMatchForCandidate(long candidateId) {
        return requestAll(applicationRepository.findIdsByCandidateIdAndStatusIn(candidateId, MATCHABLE));
    }

    /** The candidate's matchable applications to one organisation's jobs (a recruiter verified a claim). */
    @Transactional
    public int requestMatchForCandidateInOrganization(long candidateId, long organizationId) {
        return requestAll(applicationRepository.findIdsByCandidateIdAndOrganizationIdAndStatusIn(candidateId, organizationId, MATCHABLE));
    }

    private int requestAll(List<Long> applicationIds) {
        for (Long id : applicationIds) {
            backgroundJobService.enqueueFollowUp(BackgroundJobType.MATCH_APPLICATION, id);
        }
        return applicationIds.size();
    }
}
