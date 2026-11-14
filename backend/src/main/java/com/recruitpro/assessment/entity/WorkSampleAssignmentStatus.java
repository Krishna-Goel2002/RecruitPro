package com.recruitpro.assessment.entity;

/**
 * ASSIGNED &rarr; STARTED (candidate pressed Start; instructions revealed, timer runs) &rarr; SUBMITTED &rarr; SCORED.
 * EXPIRED: not started before the due date, or not submitted within the time limit plus the grace period.
 * CANCELLED: withdrawn by the account-deletion flow.
 */
public enum WorkSampleAssignmentStatus {
    ASSIGNED,
    STARTED,
    SUBMITTED,
    SCORED,
    EXPIRED,
    CANCELLED
}
