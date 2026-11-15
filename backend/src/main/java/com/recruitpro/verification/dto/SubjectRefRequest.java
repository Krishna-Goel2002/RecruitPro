package com.recruitpro.verification.dto;

/** A claim subject: {@code subjectType} RESUME_FACT or CANDIDATE_CLAIM and its id. */
public record SubjectRefRequest(String subjectType, Long subjectId) {
}
