package com.recruitpro.assistant.dto;

import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/assistant/actions/{id}/confirm: {} or {mode}. {@code mode} applies to create_job only: DRAFT (save as
 * draft) or PUBLISH; omitted = what was proposed (draft unless the user asked to publish). Ignored by other actions.
 */
public record ConfirmActionRequest(@Pattern(regexp = "(?i)DRAFT|PUBLISH") String mode) {
}
