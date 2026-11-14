package com.recruitpro.assessment.dto;

import com.recruitpro.assessment.entity.WorkSampleStatus;
import jakarta.validation.constraints.NotNull;

/** PATCH /api/v1/hr/jobs/{jobId}/work-samples/{id}/status: {status}. */
public record WorkSampleStatusRequest(@NotNull WorkSampleStatus status) {
}
