package com.recruitpro.security.ratelimit;

import java.time.Duration;

/** {@code app.rate-limit.store=memory} (default): the per-instance {@link SlidingWindowRateLimiter}. */
public class InMemoryRateLimiterStore implements RateLimiterStore {

    private final SlidingWindowRateLimiter limiter;

    public InMemoryRateLimiterStore() {
        this(new SlidingWindowRateLimiter());
    }

    public InMemoryRateLimiterStore(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public long tryAcquire(String key, int limit, Duration window) {
        return limiter.tryAcquire(key, limit, window);
    }

    @Override
    public void reset() {
        limiter.reset();
    }
}
