package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CandidateWorkSample (plan 5.5): never contains scores or the rubric. {@code instructions} is null until the
 * candidate presses Start; {@code deadlineAt} = startedAt + time limit (submissions are accepted for a further
 * 5-minute grace period). WP-PLAT added {@code files} (the candidate's own attachments, [] when none) and
 * {@code cancelledAt}/{@code cancelReason} (set when HR cancelled the assignment). {@code applicationId} (integration
 * pass, R5#8): the application the assignment belongs to, so the portal links tasks to applications without guessing.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateWorkSampleResponse(Long assignmentId, String jobTitle, String title, String instructions,
                                          int timeLimitMinutes, WorkSampleAssignmentStatus status, LocalDateTime dueAt,
                                          LocalDateTime startedAt, LocalDateTime submittedAt, LocalDateTime deadlineAt,
                                          List<WorkSampleFileResponse> files, LocalDateTime cancelledAt,
                                          String cancelReason, Long applicationId) {
}
