package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;

import java.time.LocalDateTime;

/**
 * CandidateWorkSample (plan 5.5): never contains scores or the rubric. {@code instructions} is null until the
 * candidate presses Start; {@code deadlineAt} = startedAt + time limit (submissions are accepted for a further
 * 5-minute grace period).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateWorkSampleResponse(Long assignmentId, String jobTitle, String title, String instructions,
                                          int timeLimitMinutes, WorkSampleAssignmentStatus status, LocalDateTime dueAt,
                                          LocalDateTime startedAt, LocalDateTime submittedAt, LocalDateTime deadlineAt) {
}
