package com.recruitpro.security.ratelimit;

import com.recruitpro.auth.service.TokenHashing;
import com.recruitpro.common.time.UtcTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code app.rate-limit.store=database}: fixed-window counters in {@code rate_limit_buckets}, shared by every backend
 * instance. Each request runs one short transaction of its own: {@code SELECT ... FOR UPDATE} on the bucket row, then
 * an insert (first request), a window reset or an increment. Two instances creating the same bucket at once collide
 * on the primary key; the loser retries in a new transaction (no {@code ON CONFLICT}, so it works on H2 and
 * PostgreSQL alike). A fixed window may let up to twice the limit through around a window boundary.
 */
public class DatabaseRateLimiterStore implements RateLimiterStore {

    static final int MAX_KEY_LENGTH = 200;
    static final int HOUSEKEEPING_EVERY = 500;
    private static final Logger log = LoggerFactory.getLogger(DatabaseRateLimiterStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final AtomicLong calls = new AtomicLong();

    public DatabaseRateLimiterStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public long tryAcquire(String key, int limit, Duration window) {
        String bucket = key.length() > MAX_KEY_LENGTH ? "h:" + TokenHashing.sha256(key) : key;
        if (calls.incrementAndGet() % HOUSEKEEPING_EVERY == 0) {
            purgeOld();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Long wait = tx.execute(status -> acquireOnce(bucket, limit, window));
                return wait == null ? 0L : wait;
            } catch (DataIntegrityViolationException ex) {
                // Another instance created the bucket in the meantime; the next attempt finds and locks it.
            }
        }
        log.warn("Rate-limit bucket could not be updated after 3 attempts; request allowed");
        return 0L;
    }

    private long acquireOnce(String bucket, int limit, Duration window) {
        LocalDateTime now = UtcTime.now();
        List<Bucket> rows = jdbcTemplate.query(
                "SELECT window_start, hit_count FROM rate_limit_buckets WHERE bucket_key = ? FOR UPDATE",
                (rs, rowNum) -> new Bucket(rs.getTimestamp(1).toLocalDateTime(), rs.getInt(2)), bucket);
        if (rows.isEmpty()) {
            jdbcTemplate.update("INSERT INTO rate_limit_buckets (bucket_key, window_start, hit_count) VALUES (?, ?, 1)",
                    bucket, Timestamp.valueOf(now));
            return 0L;
        }
        Bucket current = rows.get(0);
        LocalDateTime windowEnd = current.windowStart().plus(window);
        if (!windowEnd.isAfter(now)) {
            jdbcTemplate.update("UPDATE rate_limit_buckets SET window_start = ?, hit_count = 1 WHERE bucket_key = ?",
                    Timestamp.valueOf(now), bucket);
            return 0L;
        }
        if (current.count() >= limit) {
            long waitMillis = Duration.between(now, windowEnd).toMillis();
            return Math.max(1L, (waitMillis + 999L) / 1000L);
        }
        jdbcTemplate.update("UPDATE rate_limit_buckets SET hit_count = hit_count + 1 WHERE bucket_key = ?", bucket);
        return 0L;
    }

    /** Deletes buckets whose window started more than two hours ago (longest window is one hour). */
    void purgeOld() {
        try {
            tx.executeWithoutResult(status -> jdbcTemplate.update("DELETE FROM rate_limit_buckets WHERE window_start < ?",
                    Timestamp.valueOf(UtcTime.now().minusHours(2))));
        } catch (RuntimeException ex) {
            log.debug("Rate-limit housekeeping failed: {}", ex.getClass().getSimpleName());
        }
    }

    @Override
    public void reset() {
        tx.executeWithoutResult(status -> jdbcTemplate.update("DELETE FROM rate_limit_buckets"));
    }

    private record Bucket(LocalDateTime windowStart, int count) {
    }
}
