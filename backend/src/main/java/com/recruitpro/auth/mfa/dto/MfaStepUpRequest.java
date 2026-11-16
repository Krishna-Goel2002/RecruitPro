package com.recruitpro.auth.mfa.dto;

import jakarta.validation.constraints.Size;

/**
 * Step-up for sensitive MFA actions (POST /api/v1/me/mfa/recovery-codes, R1-U1): a current TOTP {@code code} or the
 * account {@code password}. At least one is required (checked by the service, so a missing body is a clear 400).
 */
public record MfaStepUpRequest(@Size(max = 72) String password, @Size(max = 32) String code) {
}
