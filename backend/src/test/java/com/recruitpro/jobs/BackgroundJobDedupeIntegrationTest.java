package com.recruitpro.jobs;

import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-instance safety of the queue (WP-PLAT): the unique dedupe key of PENDING jobs, its release when a job is
 * claimed, and the race path where another instance inserted the same key first (savepoint, no failed transaction).
 */
@SpringBootTest
@ActiveProfiles("test")
class BackgroundJobDedupeIntegrationTest {

    private static final BackgroundJobType TYPE = BackgroundJobType.MATCH_APPLICATION;

    @Autowired BackgroundJobService jobService;
    @Autowired BackgroundJobRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void pendingJobsCarryTheDedupeKeyAndDuplicatesReturnTheExistingJob() {
        BackgroundJobService.EnqueueResult first = jobService.enqueueJob(TYPE, 42L, EnumSet.of(BackgroundJobStatus.PENDING));
        assertTrue(first.created());
        assertEquals("MATCH_APPLICATION:42", first.job().getDedupeKey());
        BackgroundJobService.EnqueueResult second = jobService.enqueueJob(TYPE, 42L, EnumSet.of(BackgroundJobStatus.PENDING));
        assertFalse(second.created());
        assertEquals(first.job().getId(), second.job().getId(), "the existing job is returned");
        assertFalse(jobService.enqueueFollowUp(TYPE, 42L));
        assertEquals(1, repository.findAllByJobTypeAndEntityIdOrderByIdAsc(TYPE, 42L).size());
    }

    @Test
    void claimingReleasesTheKeySoAFollowUpCanBeQueued() {
        assertTrue(jobService.enqueueFollowUp(TYPE, 7L));
        List<BackgroundJob> claimed = jobService.claimDue(10);
        assertEquals(1, claimed.size());
        assertNull(claimed.get(0).getDedupeKey(), "a RUNNING job holds no key");
        assertFalse(jobService.enqueue(TYPE, 7L), "enqueue still treats RUNNING as a duplicate");
        assertTrue(jobService.enqueueFollowUp(TYPE, 7L), "a follow-up may be queued while the job runs");
        List<BackgroundJob> jobs = repository.findAllByJobTypeAndEntityIdOrderByIdAsc(TYPE, 7L);
        assertEquals(2, jobs.size());
        assertEquals(BackgroundJobStatus.RUNNING, jobs.get(0).getStatus());
        assertEquals("MATCH_APPLICATION:7", jobs.get(1).getDedupeKey());
    }

    @Test
    void retriedJobsAreStillDuplicatesWithoutAKey() {
        assertTrue(jobService.enqueue(TYPE, 9L));
        BackgroundJob running = jobService.claimDue(10).get(0);
        assertEquals(BackgroundJobStatus.PENDING, jobService.markAttemptFailed(running.getId(), "boom"));
        BackgroundJob retried = repository.findById(running.getId()).orElseThrow();
        assertNull(retried.getDedupeKey());
        assertFalse(jobService.enqueueFollowUp(TYPE, 9L), "the PENDING retry counts as a duplicate");
        assertEquals(1, repository.findAllByJobTypeAndEntityIdOrderByIdAsc(TYPE, 9L).size());
    }

    @Test
    void losingTheInsertRaceIsANoOpAndKeepsTheTransactionUsable() {
        // Another instance committed a PENDING job with the key between our duplicate check and our insert.
        String key = BackgroundJobService.dedupeKey(TYPE, 99L);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbcTemplate.update("INSERT INTO background_jobs (job_type, entity_id, status, attempts, max_attempts, run_after, "
                + "dedupe_key, created_at, updated_at) VALUES (?, ?, 'PENDING', 0, 5, ?, ?, ?, ?)",
                TYPE.name(), 99L, now, key, now, now);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer afterwards = tx.execute(status -> {
            assertNull(jobService.insertPending(TYPE, 99L, key), "the unique key refuses the second PENDING job");
            // The surrounding transaction keeps working (rolled back to the savepoint only).
            assertNotNull(jobService.insertPending(BackgroundJobType.PARSE_RESUME, 99L,
                    BackgroundJobService.dedupeKey(BackgroundJobType.PARSE_RESUME, 99L)));
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM background_jobs WHERE entity_id = 99", Integer.class);
        });
        assertEquals(2, afterwards);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM background_jobs WHERE dedupe_key = ?",
                Integer.class, key));
        BackgroundJobService.EnqueueResult result = jobService.enqueueJob(TYPE, 99L, EnumSet.of(BackgroundJobStatus.PENDING));
        assertFalse(result.created());
        assertEquals(key, result.job().getDedupeKey());
    }

    @Test
    void theDatabaseRejectsTwoPendingJobsWithTheSameKey() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        String sql = "INSERT INTO background_jobs (job_type, entity_id, status, attempts, max_attempts, run_after, "
                + "dedupe_key, created_at, updated_at) VALUES ('MATCH_APPLICATION', 5, 'PENDING', 0, 5, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, now, "MATCH_APPLICATION:5", now, now);
        boolean refused;
        try {
            jdbcTemplate.update(sql, now, "MATCH_APPLICATION:5", now, now);
            refused = false;
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            refused = true;
        }
        assertTrue(refused);
        jdbcTemplate.update(sql, now, null, now, now);
        jdbcTemplate.update(sql, now, null, now, now);
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM background_jobs", Integer.class),
                "rows without a key never collide");
    }
}
