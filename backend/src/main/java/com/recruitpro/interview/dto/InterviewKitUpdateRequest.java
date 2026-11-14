package com.recruitpro.interview.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/v1/hr/jobs/{jobId}/interview-kit: {questions} (0..60). Each question: text 1..2000 characters, id up to
 * 40 characters and unique (blank ids are assigned "q&lt;n&gt;"), up to 10 follow-ups of at most 1000 characters,
 * all four anchors (1..1000 characters), requirementId null or a requirement of the job.
 */
public record InterviewKitUpdateRequest(@NotNull @Size(max = 60) List<InterviewQuestion> questions) {
}
