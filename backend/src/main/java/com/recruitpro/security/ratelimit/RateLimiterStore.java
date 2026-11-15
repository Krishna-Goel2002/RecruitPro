package com.recruitpro.security.ratelimit;

import java.time.Duration;

/**
 * Where rate-limit counters live ({@code app.rate-limit.store}): {@code memory} (one instance, sliding window) or
 * {@code database} (shared by every instance, fixed window in {@code rate_limit_buckets}).
 */
public interface RateLimiterStore {

    /** Records a request for {@code key}; 0 when allowed, otherwise the seconds until a request is allowed again. */
    long tryAcquire(String key, int limit, Duration window);

    /** Clears all counters (tests). */
    void reset();
}
