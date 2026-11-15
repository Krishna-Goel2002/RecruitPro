package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * WorkSampleAssignment (plan 5.6), staff view: the candidate's response, the scores and their mean
 * ({@code totalScore}, 1.00-4.00). WP-PLAT added {@code files} (submitted attachments, [] when none) and the
 * cancellation ({@code cancelledAt}, {@code cancelReason}, {@code cancelledByName}; null unless CANCELLED by HR).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkSampleAssignmentResponse(Long id, Long workSampleId, String workSampleTitle, Long applicationId,
                                           WorkSampleAssignmentStatus status, LocalDateTime assignedAt,
                                           LocalDateTime dueAt, LocalDateTime startedAt, LocalDateTime submittedAt,
                                           String responseText, String responseUrl, List<CriterionScore> scores,
                                           BigDecimal totalScore, LocalDateTime scoredAt, String scoredByName,
                                           List<WorkSampleFileResponse> files, LocalDateTime cancelledAt,
                                           String cancelReason, String cancelledByName) {
}
