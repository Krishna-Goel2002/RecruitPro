package com.recruitpro.evidence.service;

import com.recruitpro.ai.dto.AiMatchRequest;

import java.time.LocalDateTime;

/**
 * Inputs of one MATCH_APPLICATION run, read in a short read-only transaction before the AI call.
 * {@code preparedAt} is when the inputs were read; it becomes the run's {@code calculated_at}.
 */
public record PreparedMatch(long applicationId, long jobId, long resumeAnalysisId, LocalDateTime analysisCompletedAt,
                            int requirementsVersion, AiMatchRequest request, String fingerprint, LocalDateTime preparedAt) {
}
