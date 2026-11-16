package com.recruitpro.security.headers;

import com.recruitpro.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security headers on every backend response (plan v2, Section 6.9): {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY}, {@code Referrer-Policy: no-referrer}; API and actuator responses (JSON, CSV,
 * downloads, redirects) also get {@code Content-Security-Policy: default-src 'none'; frame-ancestors 'none'}. The
 * Swagger UI (development only) is left without the CSP because it is an HTML application. Headers are set before
 * the rest of the chain runs, so 401/403/429 answers carry them too.
 */
public class ApiSecurityHeadersFilter extends OncePerRequestFilter {

    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (isApi(request)) {
            response.setHeader("Content-Security-Policy", API_CSP);
        }
        filterChain.doFilter(request, response);
    }

    static boolean isApi(HttpServletRequest request) {
        // Decoded like MVC does, so that a percent-encoded path cannot get an API answer without the CSP (R1-M2).
        String uri = RequestPaths.of(request);
        return uri.startsWith("/api/v1/") || uri.equals("/api/v1") || uri.startsWith("/actuator");
    }
}
