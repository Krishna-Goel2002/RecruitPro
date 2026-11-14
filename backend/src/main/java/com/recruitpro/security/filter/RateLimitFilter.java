package com.recruitpro.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.web.ClientIp;
import com.recruitpro.security.ratelimit.SlidingWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * S1: per-client-IP rate limits on the public authentication routes, organisation sign-up and the password-confirmed
 * candidate account deletion ({@code app.rate-limit.enabled}). Exceeding a limit returns 429 with a
 * {@code Retry-After} header.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final String AUTH_PREFIX = "/api/v1/auth/";
    static final String SIGNUP_PATH = "/api/v1/organizations/signup";
    /** DELETE only: the password confirmation of account deletion must not be guessable without limit. */
    static final String ACCOUNT_DELETION_PATH = "/api/v1/candidates/me";

    private static final List<Rule> RULES = List.of(
            new Rule("/api/v1/auth/login", 10, Duration.ofMinutes(1)),
            new Rule("/api/v1/auth/register", 5, Duration.ofMinutes(1)),
            new Rule("/api/v1/auth/refresh", 30, Duration.ofMinutes(1)),
            new Rule("/api/v1/auth/forgot-password", 5, Duration.ofMinutes(15)),
            new Rule("/api/v1/auth/accept-invite", 10, Duration.ofMinutes(1)),
            new Rule(SIGNUP_PATH, 3, Duration.ofHours(1)),
            new Rule(ACCOUNT_DELETION_PATH, 5, Duration.ofMinutes(15)));

    /** Every other route under /api/v1/auth/ (verify-email, reset-password, invitation preview, ...). */
    private static final Rule AUTH_DEFAULT = new Rule("/api/v1/auth/*", 30, Duration.ofMinutes(1));

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

    public RateLimitFilter(ObjectMapper objectMapper, @Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = path(request);
        boolean accountDeletion = path.equals(ACCOUNT_DELETION_PATH) && "DELETE".equalsIgnoreCase(request.getMethod());
        return !(path.startsWith(AUTH_PREFIX) || path.equals(SIGNUP_PATH) || accountDeletion);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Rule rule = resolve(path(request));
        String key = ClientIp.of(request) + "|" + rule.route();
        long retryAfterSeconds = limiter.tryAcquire(key, rule.limit(), rule.window());
        if (retryAfterSeconds > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            objectMapper.writeValue(response.getOutputStream(),
                    ApiResponse.error("Too many requests. Please try again later."));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Clears all counters (tests). */
    public void reset() {
        limiter.reset();
    }

    private Rule resolve(String path) {
        for (Rule rule : RULES) {
            if (rule.route().equals(path)) {
                return rule;
            }
        }
        return AUTH_DEFAULT;
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        while (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private record Rule(String route, int limit, Duration window) {
    }
}
