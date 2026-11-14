package com.recruitpro.candidate.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

/** The candidate's own resume analysis: facts, parser flags and the role timeline. */
public record ResumeAnalysisResponse(
        Long resumeId,
        ResumeAnalysisStatus status,
        String parserVersion,
        String errorCode,
        int attemptCount,
        LocalDateTime analyzedAt,
        List<ResumeAnalysisFactResponse> facts,
        List<ResumeFlagResponse> flags,
        JsonNode timeline
) {
    public static ResumeAnalysisResponse from(ResumeAnalysis analysis, List<ResumeAnalysisFactResponse> facts,
                                              List<ResumeFlagResponse> flags, JsonNode timeline) {
        return new ResumeAnalysisResponse(analysis.getResume().getId(), analysis.getStatus(), analysis.getParserVersion(),
                analysis.getErrorCode(), analysis.getAttemptCount(), analysis.getCompletedAt(), facts, flags, timeline);
    }
}
