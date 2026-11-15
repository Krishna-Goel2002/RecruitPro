package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code connectors}: GITHUB, GITLAB, WEBSITE, DOCUMENT, OPEN_BADGE, REFERENCE -> enabled.
 * {@code trustedEmployerDomains} / {@code trustedBadgeIssuers}: the organisation's own trusted domains (V26), never null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerificationSettingsResponse(String mode, Map<String, Boolean> connectors, LocalDateTime updatedAt,
                                           String updatedByName, List<String> trustedEmployerDomains,
                                           List<String> trustedBadgeIssuers) {
}
