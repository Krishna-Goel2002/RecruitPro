package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ApplicantSummary (plan 5.4). {@code displayName} is "Candidate #A-&lt;applicationId&gt;" until the identity is
 * revealed (D4). {@code matchStatus}: READY, PENDING, NO_REQUIREMENTS, ANALYSIS_UNAVAILABLE or FAILED.
 * {@code rankGroup}: RANKED, MUST_HAVES_UNVERIFIED or UNSCORED; {@code rank} is set only in RANKED.
 */
public record ApplicantSummaryResponse(Long applicationId, String displayName, boolean identityRevealed,
                                       ApplicationStatus applicationStatus, LocalDateTime appliedAt,
                                       LocalDateTime statusChangedAt, String analysisStatus, String matchStatus,
                                       Double verifiedCoverage, Double claimedCoverage, Boolean knockoutsVerified,
                                       List<Long> unverifiedKnockouts, String rankGroup, Integer rank, List<String> flags,
                                       String workSampleStatus, int interviewCount) {
}
