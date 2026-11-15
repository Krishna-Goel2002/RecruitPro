package com.recruitpro.verification.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * Browser binding of the GitHub OAuth flow (login-CSRF / account-linking fix): {@code start} sets an httpOnly cookie
 * holding a random nonce whose SHA-256 is stored with the state row; the public callback requires it, compares in
 * constant time and clears it. {@code SameSite=Lax} so the cookie travels on GitHub's top-level redirect back to the
 * callback; {@code Secure} per {@code app.auth.cookie-secure}; {@code Path} = the callback path (taken from the
 * configured redirect URI). Like the refresh cookie, this assumes the portal and the API are on the same site.
 */
@Component
public class GitHubOAuthBindingCookie {

    public static final String NAME = "rp_oauth_github";
    public static final String DEFAULT_PATH = "/api/v1/verification/oauth/github/callback";

    private final boolean secure;
    private final String path;

    public GitHubOAuthBindingCookie(@Value("${app.auth.cookie-secure:true}") boolean secure, GitHubOAuthSettings settings) {
        this.secure = secure;
        this.path = pathOf(settings.redirectUri());
    }

    static String pathOf(String redirectUri) {
        if (redirectUri != null && !redirectUri.isBlank()) {
            try {
                String p = URI.create(redirectUri.trim()).getPath();
                if (p != null && p.startsWith("/")) {
                    return p;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to the default path
            }
        }
        return DEFAULT_PATH;
    }

    public String path() {
        return path;
    }

    /** The cookie set by {@code start}, valid as long as the state. */
    public ResponseCookie issue(String nonce, Duration maxAge) {
        return ResponseCookie.from(NAME, nonce)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    /** Expires the cookie (sent on every callback answer). */
    public ResponseCookie expired() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }

    /** The presented nonce, or null. */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }
}
