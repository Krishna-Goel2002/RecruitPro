package com.recruitpro.auth.mfa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Returned by {@code POST /api/v1/auth/login} instead of an AuthResponse when a staff user must pass a second
 * step: {@code mfaRequired} is always true; {@code mfaEnrollmentRequired} is true when the organisation requires MFA
 * and the user has not enrolled yet (the token then works for {@code /me/mfa/enroll} and {@code /me/mfa/confirm}).
 * {@code mfaToken} is signed, single use and valid for {@code expiresInSeconds} (300).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MfaChallengeResponse(boolean mfaRequired, boolean mfaEnrollmentRequired, String mfaToken,
                                   long expiresInSeconds) {
}
