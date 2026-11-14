package com.recruitpro.evidence.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST …/claims/{claimId}/verification: verified=true corroborates (tier 2); false rejects the claim. */
public record ClaimVerificationRequest(@NotNull Boolean verified, @Size(max = 500) String note) {
}
