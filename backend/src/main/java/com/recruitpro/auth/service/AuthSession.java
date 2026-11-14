package com.recruitpro.auth.service;

import com.recruitpro.auth.dto.AuthResponse;

/**
 * Result of a sign-in, refresh or account creation: the response body and the raw refresh token to put in
 * the portal's refresh cookie (see {@code auth.web.Portal}) ({@code null} when the cookie must stay unchanged, e.g. the refresh grace
 * window for concurrent tabs).
 */
public record AuthSession(AuthResponse response, String refreshToken) {
}
