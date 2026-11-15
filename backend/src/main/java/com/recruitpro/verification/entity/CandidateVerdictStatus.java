package com.recruitpro.verification.entity;

/** The candidate's decision on a verdict. Only SHARED, current verdicts are ever shown to staff. */
public enum CandidateVerdictStatus {
    AWAITING_CANDIDATE, SHARED, HIDDEN, WITHDRAWN, SUPERSEDED
}
