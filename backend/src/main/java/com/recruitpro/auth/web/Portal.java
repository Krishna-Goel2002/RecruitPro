package com.recruitpro.auth.web;

import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.user.entity.Role;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * The portal a browser request comes from, sent by both frontends in the {@value #HEADER} header
 * ({@code candidate} or {@code staff}). Each portal keeps its own refresh cookie, so signing in on one portal
 * no longer replaces the other portal's session (cookies ignore ports, so {@code localhost:5173} and
 * {@code :5174} would otherwise share one cookie). A request without the header (API clients, older builds)
 * uses the original {@code rp_refresh} cookie and no portal role check.
 *
 * <p>Role rules: the candidate portal accepts only {@link Role#CANDIDATE}; the staff portal accepts every other
 * role. Sign-in, refresh, invitation acceptance, organisation sign-up and registration from the wrong portal are
 * refused with 403 before any session is created.
 */
public enum Portal {

    CANDIDATE("candidate", "rp_refresh_candidate",
            "This is the candidate portal. Staff accounts sign in to the RecruitPro HR portal."),
    STAFF("staff", "rp_refresh_staff",
            "This is the HR portal for organisation staff. Candidate accounts sign in to the candidate portal.");

    /** Request header naming the portal. */
    public static final String HEADER = "X-RecruitPro-Portal";

    /** Refresh cookie used when the request names no portal. */
    public static final String LEGACY_COOKIE_NAME = "rp_refresh";

    private final String headerValue;
    private final String cookieName;
    private final String wrongRoleMessage;

    Portal(String headerValue, String cookieName, String wrongRoleMessage) {
        this.headerValue = headerValue;
        this.cookieName = cookieName;
        this.wrongRoleMessage = wrongRoleMessage;
    }

    public String headerValue() {
        return headerValue;
    }

    public String cookieName() {
        return cookieName;
    }

    public String wrongRoleMessage() {
        return wrongRoleMessage;
    }

    /** Whether an account with this role may hold a session in this portal. */
    public boolean allows(Role role) {
        return role != null && (this == CANDIDATE) == (role == Role.CANDIDATE);
    }

    /**
     * The portal named by the request header; {@code null} when the header is absent or blank.
     *
     * @throws BadRequestException for any other value
     */
    public static Portal from(HttpServletRequest request) {
        return parse(request == null ? null : request.getHeader(HEADER));
    }

    static Portal parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Portal portal : values()) {
            if (portal.headerValue.equals(normalized)) {
                return portal;
            }
        }
        throw new BadRequestException(HEADER + " must be 'candidate' or 'staff'");
    }

    /** Cookie name for the portal, or the legacy name when the request named none. */
    public static String cookieNameFor(Portal portal) {
        return portal == null ? LEGACY_COOKIE_NAME : portal.cookieName;
    }

    /**
     * Refuses (403) an account role that does not belong to the request's portal. No-op without a portal.
     *
     * @throws ForbiddenException with a message telling the user which portal to use
     */
    public static void requireAllowed(Portal portal, Role role) {
        if (portal != null && !portal.allows(role)) {
            throw new ForbiddenException(portal.wrongRoleMessage);
        }
    }
}
