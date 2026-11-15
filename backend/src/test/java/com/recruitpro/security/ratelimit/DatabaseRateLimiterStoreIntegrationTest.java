package com.recruitpro.security.ratelimit;

import com.recruitpro.security.filter.RateLimitFilter;
import com.recruitpro.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** app.rate-limit.store=database: shared fixed-window counters in rate_limit_buckets (works on H2 and PostgreSQL). */
@SpringBootTest(properties = {"app.rate-limit.enabled=true", "app.rate-limit.store=database"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseRateLimiterStoreIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RateLimiterStore store;
    @Autowired RateLimitFilter rateLimitFilter;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        rateLimitFilter.reset();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void theDatabaseStoreIsSelectedAndCountsPerKey() {
        assertInstanceOf(DatabaseRateLimiterStore.class, store);
        Duration minute = Duration.ofMinutes(1);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, store.tryAcquire("1.2.3.4|/login", 3, minute));
        }
        long retryAfter = store.tryAcquire("1.2.3.4|/login", 3, minute);
        assertTrue(retryAfter > 0 && retryAfter <= 60, "retry after the window: " + retryAfter);
        assertEquals(0, store.tryAcquire("5.6.7.8|/login", 3, minute), "other keys are independent");
        assertEquals(3, jdbcTemplate.queryForObject("SELECT hit_count FROM rate_limit_buckets WHERE bucket_key = ?",
                Integer.class, "1.2.3.4|/login"));

        // A new window starts once the old one has passed.
        jdbcTemplate.update("UPDATE rate_limit_buckets SET window_start = ? WHERE bucket_key = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2)), "1.2.3.4|/login");
        assertEquals(0, store.tryAcquire("1.2.3.4|/login", 3, minute));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT hit_count FROM rate_limit_buckets WHERE bucket_key = ?",
                Integer.class, "1.2.3.4|/login"));
    }

    @Test
    void longKeysAreHashed() {
        String key = "x".repeat(500);
        assertEquals(0, store.tryAcquire(key, 1, Duration.ofMinutes(1)));
        assertTrue(store.tryAcquire(key, 1, Duration.ofMinutes(1)) > 0);
    }

    @Test
    void concurrentRequestsNeverExceedTheLimit() throws Exception {
        DatabaseRateLimiterStore shared = new DatabaseRateLimiterStore(jdbcTemplate, transactionManager);
        // The bucket exists before the threads start, so they all contend on SELECT ... FOR UPDATE of one row.
        assertEquals(0, shared.tryAcquire("9.9.9.9|/api/v1/auth/login", 12, Duration.ofMinutes(1)));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> shared.tryAcquire("9.9.9.9|/api/v1/auth/login", 12, Duration.ofMinutes(1)));
            }
            int allowed = 0;
            for (Future<Long> result : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                if (result.get() == 0L) {
                    allowed++;
                }
            }
            assertEquals(11, allowed, "12 in total, one used before the threads started");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theFilterAnswers429FromTheSharedStore() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.test\"}")).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
