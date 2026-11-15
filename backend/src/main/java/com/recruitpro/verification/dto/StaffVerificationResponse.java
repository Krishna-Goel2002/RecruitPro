package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** GET /api/v1/hr/applications/{applicationId}/verification. In SHADOW mode {@code claims} is empty. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StaffVerificationResponse(String mode, boolean revealed, List<StaffClaim> claims) {
}
