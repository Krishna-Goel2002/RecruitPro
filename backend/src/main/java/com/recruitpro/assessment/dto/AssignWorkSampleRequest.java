package com.recruitpro.assessment.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * POST /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/work-samples: {workSampleId, dueAt?}. {@code dueAt} is the
 * latest time the candidate may start (ISO-8601 with offset, converted to UTC); it must be in the future.
 */
public record AssignWorkSampleRequest(@NotNull Long workSampleId, LocalDateTime dueAt) {
}
