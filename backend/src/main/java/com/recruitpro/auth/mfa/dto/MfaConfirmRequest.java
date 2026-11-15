package com.recruitpro.auth.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/me/mfa/confirm: the first 6-digit code; {@code mfaToken} as for enroll. */
public record MfaConfirmRequest(@NotBlank @Size(max = 12) String code, @Size(max = 2048) String mfaToken) {
}
