package com.recruitpro.auth.mfa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * GET /api/v1/me/mfa: {@code enabled} (confirmed), {@code pendingEnrollment} (enrolled but not confirmed yet),
 * {@code recoveryCodesRemaining}, {@code requiredByOrganization} and {@code available} (false when the server has no
 * encryption key, so MFA cannot be enrolled).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MfaStatusResponse(boolean enabled, LocalDateTime enabledAt, boolean pendingEnrollment,
                                int recoveryCodesRemaining, boolean requiredByOrganization, boolean available) {
}
