package com.recruitpro.evidence.dto;

import com.recruitpro.evidence.entity.ClaimType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/candidates/me/claims: {claimType, value, evidenceUrl?}. */
public record CandidateClaimCreateRequest(
        @NotNull ClaimType claimType,
        @NotBlank @Size(max = 1000) String value,
        @Size(max = 500) @Pattern(regexp = "^$|https?://\\S+", message = "must be an http or https URL") String evidenceUrl) {
}
