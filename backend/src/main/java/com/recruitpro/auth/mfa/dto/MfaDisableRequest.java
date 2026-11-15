package com.recruitpro.auth.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/me/mfa/disable: current password plus a current TOTP code or an unused recovery code. */
public record MfaDisableRequest(@NotBlank @Size(max = 72) String password, @NotBlank @Size(max = 32) String code) {
}
