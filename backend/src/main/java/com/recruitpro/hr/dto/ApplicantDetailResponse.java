package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.evidence.dto.CandidateClaimResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ApplicantDetail (plan 5.4) = ApplicantSummary fields + evidence, claims, revealed identity, cover letter,
 * resume availability, work samples and interviews (WP-BE-C extension), history and allowed decisions for the
 * caller. Extra field {@code revealAllowed}: whether POST …/reveal would reveal a hidden identity for the caller
 * (manager, blind review on, not yet revealed, status no longer SUBMITTED).
 */
public record ApplicantDetailResponse(
        Long applicationId, String displayName, boolean identityRevealed, ApplicationStatus applicationStatus,
        LocalDateTime appliedAt, LocalDateTime statusChangedAt, String analysisStatus, String matchStatus,
        Double verifiedCoverage, Double claimedCoverage, Boolean knockoutsVerified, List<Long> unverifiedKnockouts,
        String rankGroup, Integer rank, List<String> flags, String workSampleStatus, int interviewCount,
        List<RequirementEvidenceResponse> requirements, Long matchRunId, String algorithmVersion,
        LocalDateTime calculatedAt, List<CandidateClaimResponse> claims, CandidateIdentityResponse candidate,
        String coverLetter, boolean resumeAvailable, String resumeFilename, List<Object> workSamples,
        List<Object> interviews, List<DecisionEventResponse> history, List<ApplicationDecisionAction> allowedActions,
        boolean revealAllowed) {

    public static ApplicantDetailResponse of(ApplicantSummaryResponse s, List<RequirementEvidenceResponse> requirements,
                                             Long matchRunId, String algorithmVersion, LocalDateTime calculatedAt,
                                             List<CandidateClaimResponse> claims, CandidateIdentityResponse candidate,
                                             String coverLetter, boolean resumeAvailable, String resumeFilename,
                                             List<Object> workSamples, List<Object> interviews,
                                             List<DecisionEventResponse> history,
                                             List<ApplicationDecisionAction> allowedActions, boolean revealAllowed) {
        return new ApplicantDetailResponse(s.applicationId(), s.displayName(), s.identityRevealed(), s.applicationStatus(),
                s.appliedAt(), s.statusChangedAt(), s.analysisStatus(), s.matchStatus(), s.verifiedCoverage(),
                s.claimedCoverage(), s.knockoutsVerified(), s.unverifiedKnockouts(), s.rankGroup(), s.rank(), s.flags(),
                s.workSampleStatus(), s.interviewCount(), requirements, matchRunId, algorithmVersion, calculatedAt, claims,
                candidate, coverLetter, resumeAvailable, resumeFilename, workSamples, interviews, history, allowedActions,
                revealAllowed);
    }
}
