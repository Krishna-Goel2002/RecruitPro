package com.recruitpro.candidate.dto;

import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ResumeAnalysisResponse(
        Long resumeId,
        ResumeAnalysisStatus status,
        String parserVersion,
        String errorCode,
        int attemptCount,
        LocalDateTime analyzedAt,
        List<ResumeAnalysisFactResponse> facts
) {
    public static ResumeAnalysisResponse from(ResumeAnalysis analysis, List<ResumeAnalysisFact> facts) {
        return new ResumeAnalysisResponse(analysis.getResume().getId(), analysis.getStatus(), analysis.getParserVersion(),
                analysis.getErrorCode(), analysis.getAttemptCount(), analysis.getCompletedAt(),
                facts.stream().map(ResumeAnalysisFactResponse::from).toList());
    }
}
