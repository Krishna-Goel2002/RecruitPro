package com.recruitpro.verification.service;

import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.security.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-client-IP limits of the public verification endpoints (referee page, OAuth callback) and a per-candidate
 * limit of ownership checks, built on the same
 * {@link SlidingWindowRateLimiter} as {@code RateLimitFilter} and switched by the same {@code app.rate-limit.enabled}.
 * Kept here so the shared filter stays untouched; WP-PLAT may move these rules into the filter (and its store).
 */
@Component
public class PublicEndpointRateLimiter {

    public static final Rule REFERENCE_VIEW = new Rule("/api/v1/public/references:GET", 30, Duration.ofMinutes(1));
    public static final Rule REFERENCE_SUBMIT = new Rule("/api/v1/public/references:POST", 10, Duration.ofMinutes(15));
    public static final Rule OAUTH_CALLBACK = new Rule("/api/v1/verification/oauth/github/callback", 30, Duration.ofMinutes(1));
    /** Per candidate (key {@code candidate:<id>}): each check makes the agent service fetch a page, DNS or a profile. */
    public static final Rule OWNERSHIP_CHECK = new Rule("ownership-check", 20, Duration.ofHours(1));

    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
    private final boolean enabled;

    public PublicEndpointRateLimiter(@Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** A route and its limit per window. */
    public record Rule(String route, int limit, Duration window) {
    }

    /** Throws 429 when the client exceeded the rule. */
    public void check(Rule rule, String clientIp) {
        if (!enabled) {
            return;
        }
        long retryAfter = limiter.tryAcquire((clientIp == null ? "unknown" : clientIp) + "|" + rule.route(), rule.limit(), rule.window());
        if (retryAfter > 0) {
            throw new TooManyRequestsException("Too many requests. Please try again later.");
        }
    }

    /** Clears all counters (tests). */
    public void reset() {
        limiter.reset();
    }
}
