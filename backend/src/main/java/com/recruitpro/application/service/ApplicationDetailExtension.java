package com.recruitpro.application.service;

import com.recruitpro.application.entity.Application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Extension point for verification modules (WP-BE-C) to add work samples and interviews to the applicant views.
 * Every Spring bean implementing it is consulted in {@code @Order}; lists are concatenated and summary extras
 * merged (first non-null work-sample status, interview counts added). All methods run inside a read-only
 * transaction of a GET request and must not write or call the AI service.
 *
 * <ul>
 *   <li>{@link #applicantExtras}: HR applicant list (one call per page, batch by application id) &rarr;
 *       {@code workSampleStatus} and {@code interviewCount} of ApplicantSummary.</li>
 *   <li>{@link #staffWorkSamples} / {@link #staffInterviews}: ApplicantDetail {@code workSamples}
 *       (WorkSampleAssignment[]) and {@code interviews} (Interview[]), plan 5.6.</li>
 *   <li>{@link #candidateWorkSamples} / {@link #candidateInterviews}: candidate ApplicationDetail
 *       {@code workSamples} (CandidateWorkSample[]) and {@code interviews} ({id, scheduledAt, durationMinutes,
 *       location, status}[]).</li>
 * </ul>
 * Returned objects are serialised with Jackson as they are.
 */
public interface ApplicationDetailExtension {

    default Map<Long, ApplicantExtras> applicantExtras(Collection<Long> applicationIds) {
        return Map.of();
    }

    default List<?> staffWorkSamples(Application application) {
        return List.of();
    }

    default List<?> staffInterviews(Application application) {
        return List.of();
    }

    default List<?> candidateWorkSamples(Application application) {
        return List.of();
    }

    default List<?> candidateInterviews(Application application) {
        return List.of();
    }

    /** Summary fields of one applicant: latest work-sample assignment status (or null) and number of interviews. */
    record ApplicantExtras(String workSampleStatus, int interviewCount) {
    }
}
