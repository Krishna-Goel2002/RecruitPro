package com.recruitpro.candidate.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.evidence.entity.EvidenceTier;

import java.time.LocalDateTime;

/**
 * A resume fact as the candidate sees it. {@code extractedValue} is what the parser found and is never
 * overwritten; {@code tier} is the fact's evidence tier (RESUME_STATED); {@code claimedValue} is the
 * candidate's correction, which is a SELF_CLAIMED claim ({@code claimId}) until a recruiter verifies it.
 * {@code value} is the value displayed (the claimed value when there is one, else the extracted value).
 */
public record ResumeAnalysisFactResponse(
        Long id,
        String type,
        String value,
        String extractedValue,
        String evidence,
        String sourceSection,
        String sourceReference,
        ResumeFactReviewStatus reviewStatus,
        LocalDateTime reviewedAt,
        EvidenceTier tier,
        String claimedValue,
        Long claimId,
        Integer spanStart,
        Integer spanEnd,
        JsonNode attributes
) {
    public static ResumeAnalysisFactResponse from(ResumeAnalysisFact fact, Long claimId, JsonNode attributes) {
        String value = fact.getReviewedValue() == null ? fact.getExtractedValue() : fact.getReviewedValue();
        return new ResumeAnalysisFactResponse(fact.getId(), fact.getType().name(), value,
                fact.getExtractedValue(), fact.getEvidence(), fact.getSourceSection(), fact.getSourceReference(),
                fact.getReviewStatus(), fact.getReviewedAt(),
                fact.getEvidenceTier() == null ? EvidenceTier.RESUME_STATED : fact.getEvidenceTier(),
                fact.getReviewedValue(), claimId, fact.getSpanStart(), fact.getSpanEnd(), attributes);
    }
}
