package com.recruitpro.assistant.service;

import com.recruitpro.assistant.AssistantSettings;
import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.security.ratelimit.RateLimiterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * At most {@code app.assistant.max-messages-per-hour} (30) user messages per user per hour (ASSISTANT_PLAN 1 "Cost and
 * abuse"); above it 429 with Retry-After. Uses the shared {@link RateLimiterStore} ({@code app.rate-limit.store}: memory
 * or database, so several backend instances share the count). Independent of {@code app.rate-limit.enabled}, which
 * only governs the IP limits of the public routes; 0 switches it off.
 */
@Component
public class AssistantRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(AssistantRateLimiter.class);

    private final RateLimiterStore store;
    private final AssistantSettings settings;

    public AssistantRateLimiter(RateLimiterStore store, AssistantSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** Counts one user message; throws 429 when the user is over the limit. */
    public void acquire(Long userId) {
        int limit = settings.maxMessagesPerHour();
        if (limit <= 0) {
            return;
        }
        long retryAfter;
        try {
            retryAfter = store.tryAcquire("assistant-messages|user:" + userId, limit, WINDOW);
        } catch (RuntimeException ex) {
            // A store failure (e.g. the database is briefly unavailable) must not lock everybody out.
            log.warn("Assistant rate limiter unavailable, message allowed: {}", ex.getClass().getSimpleName());
            return;
        }
        if (retryAfter > 0) {
            throw new TooManyRequestsException("You sent " + limit + " messages to the assistant in the last hour. "
                    + "Please try again later.", retryAfter);
        }
    }
}
