package com.recruitpro.auth.service;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.mfa.dto.MfaChallengeResponse;

/**
 * Result of a sign-in, refresh or account creation: the response body and the raw refresh token to put in
 * the portal's refresh cookie (see {@code auth.web.Portal}) ({@code null} when the cookie must stay unchanged, e.g. the refresh grace
 * window for concurrent tabs). A staff sign-in that needs a second factor (WP-PLAT) carries only
 * {@code mfaChallenge}: no session exists yet, {@code response} and {@code refreshToken} are null.
 */
public record AuthSession(AuthResponse response, String refreshToken, MfaChallengeResponse mfaChallenge) {

    public AuthSession(AuthResponse response, String refreshToken) {
        this(response, refreshToken, null);
    }

    /** A sign-in that stops before the session: the client continues with {@code /auth/mfa/verify} or MFA enrolment. */
    public static AuthSession challenge(MfaChallengeResponse challenge) {
        return new AuthSession(null, null, challenge);
    }

    public boolean requiresMfa() {
        return mfaChallenge != null;
    }
}
