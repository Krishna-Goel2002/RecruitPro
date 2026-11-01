package com.recruitpro.candidate.dto;

import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;

import java.time.LocalDateTime;

public record ResumeAnalysisFactResponse(
        Long id,
        String type,
        String value,
        String extractedValue,
        String evidence,
        String sourceSection,
        String sourceReference,
        ResumeFactReviewStatus reviewStatus,
        LocalDateTime reviewedAt
) {
    public static ResumeAnalysisFactResponse from(ResumeAnalysisFact fact) {
        String value = fact.getReviewedValue() == null ? fact.getExtractedValue() : fact.getReviewedValue();
        return new ResumeAnalysisFactResponse(fact.getId(), fact.getType().name(), value,
                fact.getExtractedValue(), fact.getEvidence(), fact.getSourceSection(), fact.getSourceReference(),
                fact.getReviewStatus(), fact.getReviewedAt());
    }
}
