package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/match response (algorithm {@code evidence-ladder-3}). {@code verifiedCoverage} counts requirements
 * matched at tier RESUME_STATED or higher, {@code claimedCoverage} counts matches at any tier, {@code score}
 * equals {@code verifiedCoverage} (compatibility). Unknown properties are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiMatchResponse(String algorithmVersion, String scoreLabel, Double verifiedCoverage, Double claimedCoverage,
                              Boolean knockoutsVerified, List<Long> unverifiedKnockouts, Double score,
                              List<RequirementResult> requirements) {

    /**
     * Result for one requirement: status MATCHED, UNCLEAR or NOT_OBSERVED; tier of the weakest fact still needed
     * for the match; provenance RESUME, CANDIDATE_CLAIM, CORROBORATED, WORK_SAMPLE or INTERVIEW.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequirementResult(Long requirementId, String status, String tier, String evidence, List<String> factIds,
                                    String sourceSection, String provenance, String explanation,
                                    String verificationHint) { }
}
