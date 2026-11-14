package com.recruitpro.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/candidates/me/work-samples/{assignmentId}/submit: {responseText, responseUrl?}. */
public record WorkSampleSubmitRequest(
        @NotBlank @Size(max = 50000) String responseText,
        @Size(max = 500) @Pattern(regexp = "^$|https?://\\S+", message = "must be an http or https URL") String responseUrl) {
}
