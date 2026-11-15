package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

/** {@code connectors}: GITHUB, GITLAB, WEBSITE, DOCUMENT, OPEN_BADGE, REFERENCE -> enabled. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerificationSettingsResponse(String mode, Map<String, Boolean> connectors, LocalDateTime updatedAt,
                                           String updatedByName) {
}
