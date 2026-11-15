package com.recruitpro.auth.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/mfa/verify: the login's {@code mfaToken} with either {@code code} (TOTP) or {@code recoveryCode}. */
public record MfaVerifyRequest(@NotBlank @Size(max = 2048) String mfaToken, @Size(max = 12) String code,
                               @Size(max = 32) String recoveryCode) {
}
