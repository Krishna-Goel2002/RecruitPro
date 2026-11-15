package com.recruitpro.auth.mfa.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/me/mfa/enroll. Body optional; {@code mfaToken} only when the login answered
 * {@code mfaEnrollmentRequired} (no access token yet).
 */
public record MfaEnrollRequest(@Size(max = 2048) String mfaToken) {
}
