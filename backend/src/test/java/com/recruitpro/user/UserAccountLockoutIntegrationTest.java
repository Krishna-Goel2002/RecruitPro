package com.recruitpro.user;

import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R1-H1 / R1-H2: the failed sign-in counter. Parallel failures must each count (the row is locked, no lost updates),
 * and a failure recorded inside a caller's transaction that then rolls back (a 400/401 answer) must stay counted.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAccountLockoutIntegrationTest {

    @Autowired UserAccountService userAccountService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private Long userId;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        userId = users.save(User.builder().email("parallel@example.test").passwordHash("test-only-not-a-bcrypt-hash")
                .firstName("Para").lastName("Llel").role(Role.CANDIDATE).enabled(true).build()).getId();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void parallelFailuresAreAllCounted() throws Exception {
        int threads = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    userAccountService.recordFailedLogin(userId);
                    return null;
                });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> call : calls) {
                futures.add(pool.submit(call));
            }
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, users.findById(userId).orElseThrow().getFailedLoginCount(),
                "every one of the parallel failures is counted");
    }

    @Test
    void aFailureSurvivesTheRollbackOfTheCallersTransaction() {
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.executeWithoutResult(status -> {
            userAccountService.recordFailedLogin(userId);
            status.setRollbackOnly();
        });
        assertEquals(1, users.findById(userId).orElseThrow().getFailedLoginCount());
    }

    @Test
    void theFifthFailureLocksTheAccount() {
        for (int i = 0; i < 5; i++) {
            userAccountService.recordFailedLogin(userId);
        }
        User locked = users.findById(userId).orElseThrow();
        assertNotNull(locked.getLockedUntil());
        assertEquals(0, locked.getFailedLoginCount());
        assertEquals(true, UserAccountService.isLocked(locked));
    }
}
