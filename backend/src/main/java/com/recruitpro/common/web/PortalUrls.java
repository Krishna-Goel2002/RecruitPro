package com.recruitpro.common.web;

/**
 * Builds links into the web portals from the configured base URLs ({@code app.frontend.candidate-url},
 * {@code app.frontend.hr-url}, {@code app.sso.redirect-base-url}). A base may carry a path: behind the single-origin
 * gateway the candidate portal is {@code http://localhost:8088/candidate}, so a referee link is
 * {@code http://localhost:8088/candidate/reference/<token>}. Every link builder goes through here so the sub-path is
 * never lost and there is exactly one slash between base and path, whatever trailing slashes the setting has.
 */
public final class PortalUrls {

    private PortalUrls() {
    }

    /** The base URL without surrounding whitespace and without trailing slashes ({@code ""} for null). */
    public static String base(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /** {@code base} + {@code path} with exactly one slash between them ({@code path} may start with "/" or not). */
    public static String join(String base, String path) {
        String p = path == null ? "" : path;
        if (p.isEmpty()) {
            return base(base);
        }
        return base(base) + (p.startsWith("/") ? p : "/" + p);
    }
}
