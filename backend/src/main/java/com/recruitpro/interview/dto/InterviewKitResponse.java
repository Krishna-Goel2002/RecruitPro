package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** InterviewKit (plan 5.6). {@code generatedBy}: "rules" or "llm" (AI engine) or "manual" after an edit. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InterviewKitResponse(Long jobId, List<InterviewQuestion> questions, String generatedBy,
                                   LocalDateTime updatedAt) {
}
