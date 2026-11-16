package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.assessment.entity.WorkSampleStatus;

import java.time.LocalDateTime;
import java.util.List;

/** WorkSample (plan 5.6), staff view. {@code assignmentCount}: assignments in any status (0 = may be deleted or go back to draft). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkSampleResponse(Long id, Long jobId, String title, String instructions, int timeLimitMinutes,
                                 List<RubricCriterion> rubric, WorkSampleStatus status, LocalDateTime createdAt,
                                 LocalDateTime updatedAt, long assignmentCount) {
}
