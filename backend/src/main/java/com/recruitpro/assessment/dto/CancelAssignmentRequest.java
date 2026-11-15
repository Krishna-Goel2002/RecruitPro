package com.recruitpro.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/hr/work-sample-assignments/{id}/cancel: {@code {reason}} (1-1000 characters, sent to the candidate). */
public record CancelAssignmentRequest(@NotBlank @Size(max = 1000) String reason) {
}
