package com.recruitpro.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * The request path as Spring MVC matches it: without the context path, percent-decoded and without trailing slashes.
 * Filters that decide by path (rate limits, security headers) must use this form. The raw
 * {@code getRequestURI()} is still percent-encoded, so {@code /api/v1/me/p%61ssword} reaches the password-change
 * handler while a raw comparison with {@code /api/v1/me/password} misses it (R1-M2).
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    public static String of(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.indexOf('%') >= 0) {
            try {
                uri = UriUtils.decode(uri, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                // Not a valid encoding: MVC cannot map it either; compare the raw form.
            }
        }
        while (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }
}
