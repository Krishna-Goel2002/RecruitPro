package com.recruitpro.auth.web;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.dto.RefreshTokenRequest;
import com.recruitpro.auth.service.AuthSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * D11: the refresh token lives only in an httpOnly cookie (SameSite=Strict, Path=/api/v1/auth, Secure per
 * {@code app.auth.cookie-secure}). Each portal has its own cookie, chosen by the {@link Portal#HEADER} request
 * header: {@code rp_refresh_candidate} for the candidate portal, {@code rp_refresh_staff} for the HR portal, and
 * the original {@code rp_refresh} when the header is absent. When {@code app.auth.expose-refresh-token-in-body=true}
 * the token is also returned in, and accepted from, the body.
 */
@Component
public class RefreshTokenCookies {

    /** Cookie used by requests without a portal header (kept for API clients and older builds). */
    public static final String COOKIE_NAME = Portal.LEGACY_COOKIE_NAME;
    public static final String COOKIE_PATH = "/api/v1/auth";

    private final boolean secure;
    private final boolean exposeInBody;
    private final long refreshTokenExpiryMs;

    public RefreshTokenCookies(@Value("${app.auth.cookie-secure:true}") boolean secure,
                               @Value("${app.auth.expose-refresh-token-in-body:false}") boolean exposeInBody,
                               @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs) {
        this.secure = secure;
        this.exposeInBody = exposeInBody;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

    /**
     * Writes the session's refresh token (when there is a new one) into the portal's cookie and returns the
     * response body.
     */
    public AuthResponse complete(AuthSession session, Portal portal, HttpServletResponse response) {
        AuthResponse body = session.response();
        if (session.refreshToken() != null) {
            write(response, portal, session.refreshToken());
            if (exposeInBody) {
                body.setRefreshToken(session.refreshToken());
            }
        }
        return body;
    }

    public void write(HttpServletResponse response, Portal portal, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from(Portal.cookieNameFor(portal), rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ofMillis(refreshTokenExpiryMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Expires the portal's cookie (only that one: the other portal's session is left alone). */
    public void clear(Portal portal, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(Portal.cookieNameFor(portal), "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * The presented refresh token: the portal's cookie (only that cookie, so one portal can never use the other's
     * session), or the body when body tokens are enabled.
     */
    public String resolve(HttpServletRequest request, Portal portal, RefreshTokenRequest body) {
        String name = Portal.cookieNameFor(portal);
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        if (exposeInBody && body != null && body.getRefreshToken() != null && !body.getRefreshToken().isBlank()) {
            return body.getRefreshToken().trim();
        }
        return null;
    }
}
