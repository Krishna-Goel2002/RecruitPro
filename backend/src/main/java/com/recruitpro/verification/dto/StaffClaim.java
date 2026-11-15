package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A claim with its shared verdicts; {@code tier} is the tier for the caller's organisation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StaffClaim(String subjectType, Long subjectId, String claimType, String value, String tier,
                         List<StaffVerdict> verdicts) {
}
