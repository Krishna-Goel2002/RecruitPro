package com.recruitpro.interview.repository;

/** Number of (not cancelled) interviews of one application (HR applicant list extras). */
public record InterviewCountRow(Long applicationId, Long interviews) {
}
