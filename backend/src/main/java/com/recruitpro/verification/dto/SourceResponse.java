package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A connected source (plan v2 section 5). Never carries the access token or the stored file path. Extra fields
 * beyond the contract: {@code ownershipCheckedAt}, {@code ownershipDetail} (outcome of the last check),
 * {@code createdAt}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SourceResponse(Long id, String kind, String identifier, String displayLabel, String ownershipStatus,
                             String ownershipMethod, String challengeCode, List<String> ownershipInstructions,
                             LocalDateTime consentedAt, LastRun lastRun, LocalDateTime ownershipCheckedAt,
                             String ownershipDetail, LocalDateTime createdAt) {
}
