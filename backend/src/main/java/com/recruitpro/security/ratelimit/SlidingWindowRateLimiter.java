package com.recruitpro.security.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory sliding-window limiter (S1). Each key keeps the timestamps of its accepted requests inside the
 * window. Suitable for a single backend instance; a shared store is needed when scaling out.
 */
public class SlidingWindowRateLimiter {

    private static final int HOUSEKEEPING_THRESHOLD = 10_000;
    private static final long MAX_WINDOW_MILLIS = Duration.ofHours(1).toMillis();

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records a request for {@code key}. Returns 0 when the request is allowed, otherwise the number of
     * seconds after which the oldest request leaves the window.
     */
    public long tryAcquire(String key, int limit, Duration window) {
        long now = clock.getAsLong();
        long windowMillis = window.toMillis();
        long cutoff = now - windowMillis;
        if (hits.size() > HOUSEKEEPING_THRESHOLD) {
            purge(now);
        }
        Deque<Long> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                long oldest = timestamps.peekFirst();
                long waitMillis = Math.max(0L, oldest + windowMillis - now);
                return Math.max(1L, (waitMillis + 999L) / 1000L);
            }
            timestamps.addLast(now);
            return 0L;
        }
    }

    public void reset() {
        hits.clear();
    }

    private void purge(long now) {
        long cutoff = now - MAX_WINDOW_MILLIS;
        hits.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                return timestamps.isEmpty() || timestamps.peekLast() <= cutoff;
            }
        });
    }
}
