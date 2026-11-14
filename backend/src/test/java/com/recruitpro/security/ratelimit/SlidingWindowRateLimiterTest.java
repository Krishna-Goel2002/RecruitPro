package com.recruitpro.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {

    @Test
    void allowsTheLimitWithinTheWindowAndSlidesForward() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock::get);
        Duration window = Duration.ofMinutes(1);

        for (int i = 0; i < 3; i++) {
            assertEquals(0L, limiter.tryAcquire("ip|login", 3, window));
            clock.addAndGet(1_000L);
        }
        long retryAfter = limiter.tryAcquire("ip|login", 3, window);
        assertTrue(retryAfter > 0 && retryAfter <= 60, "retry after " + retryAfter);
        assertEquals(0L, limiter.tryAcquire("other-ip|login", 3, window), "keys are independent");

        clock.addAndGet(57_001L);
        assertEquals(0L, limiter.tryAcquire("ip|login", 3, window), "the oldest request left the window");
        assertTrue(limiter.tryAcquire("ip|login", 3, window) > 0);
    }

    @Test
    void resetClearsAllCounters() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(() -> 5L);
        assertEquals(0L, limiter.tryAcquire("k", 1, Duration.ofSeconds(10)));
        assertTrue(limiter.tryAcquire("k", 1, Duration.ofSeconds(10)) > 0);
        limiter.reset();
        assertEquals(0L, limiter.tryAcquire("k", 1, Duration.ofSeconds(10)));
    }
}
