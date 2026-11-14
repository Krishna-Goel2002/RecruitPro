package com.recruitpro.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/v1/hr/jobs/{jobId}/work-samples/{id} (DRAFT only). Rubric: 1..30 criteria; each criterion 1..500
 * characters, all four anchors 1..1000 characters, {@code requirementId} null or a requirement of the job.
 */
public record WorkSampleUpdateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 20000) String instructions,
        @NotNull @Min(1) @Max(480) Integer timeLimitMinutes,
        @NotNull @Size(min = 1, max = 30) List<RubricCriterion> rubric) {
}
