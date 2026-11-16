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
    void housekeepingOfManyKeysRunsAtMostOncePerInterval() {
        // R1-L4: with more than 10,000 keys the full scan must not run on every request.
        long t0 = 10_000_000L;
        AtomicLong clock = new AtomicLong(t0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock::get);
        Duration window = Duration.ofMinutes(1);
        for (int i = 0; i <= 10_000; i++) {
            limiter.tryAcquire("a-" + i, 5, window);
        }
        assertEquals(10_001, limiter.size());

        // 20 s later: the first request above the threshold scans (nothing has expired yet), the rest do not.
        clock.set(t0 + 20_000L);
        for (int i = 0; i <= 10_000; i++) {
            limiter.tryAcquire("b-" + i, 5, window);
        }
        assertEquals(20_002, limiter.size());

        // One hour and 10 s after t0: a scan removes the "a" keys; the "b" keys are still inside the hour.
        clock.set(t0 + 3_610_000L);
        limiter.tryAcquire("c-1", 5, window);
        assertEquals(10_002, limiter.size());

        // 15 s later the "b" keys have expired too, but the last scan was less than 30 s ago: no scan.
        clock.set(t0 + 3_625_000L);
        limiter.tryAcquire("c-2", 5, window);
        assertEquals(10_003, limiter.size(), "no full scan within the interval");

        // 30 s after the last scan the next request scans again.
        clock.set(t0 + 3_610_000L + SlidingWindowRateLimiter.PURGE_INTERVAL_MILLIS);
        limiter.tryAcquire("c-3", 5, window);
        assertEquals(3, limiter.size());
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
