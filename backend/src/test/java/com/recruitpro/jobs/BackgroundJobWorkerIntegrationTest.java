package com.recruitpro.jobs;

import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.jobs.service.BackgroundJobHandler;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.jobs.service.BackgroundJobWorker;
import com.recruitpro.jobs.service.NonRetryableJobException;
import com.recruitpro.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** D7 / M7: enqueue de-duplication, retry with exponential backoff, dead letter, stale-lock recovery. */
@SpringBootTest
@ActiveProfiles("test")
class BackgroundJobWorkerIntegrationTest {

    private static final BackgroundJobType TYPE = BackgroundJobType.MATCH_APPLICATION;

    @Autowired BackgroundJobService jobService;
    @Autowired BackgroundJobRepository jobRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void enqueueIgnoresDuplicatesWhileAJobIsPendingOrRunning() {
        assertTrue(jobService.enqueue(TYPE, 41L));
        assertFalse(jobService.enqueue(TYPE, 41L));
        assertTrue(jobService.enqueue(TYPE, 42L));
        assertTrue(jobService.enqueue(BackgroundJobType.PARSE_RESUME, 41L), "types are de-duplicated separately");
        BackgroundJob job = only(TYPE, 41L);
        assertEquals(BackgroundJobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getAttempts());
        assertEquals(5, job.getMaxAttempts());
    }

    @Test
    void successfulJobIsDone() {
        RecordingHandler handler = new RecordingHandler(0, false);
        BackgroundJobWorker worker = worker(handler);
        jobService.enqueue(TYPE, 7L);

        assertEquals(1, worker.runOnce());
        BackgroundJob job = only(TYPE, 7L);
        assertEquals(BackgroundJobStatus.DONE, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertNull(job.getLockedAt());
        assertEquals(List.of(7L), handler.handled);
        assertTrue(jobService.enqueue(TYPE, 7L), "a finished job does not block new work for the entity");
    }

    @Test
    void failuresRetryWithExponentialBackoffThenDeadLetter() {
        RecordingHandler handler = new RecordingHandler(Integer.MAX_VALUE, false);
        BackgroundJobWorker worker = worker(handler);
        jobService.enqueue(TYPE, 9L);

        long[] expectedBackoffSeconds = {30, 60, 120, 240};
        for (int attempt = 1; attempt <= 4; attempt++) {
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            assertEquals(1, worker.runOnce());
            BackgroundJob job = only(TYPE, 9L);
            assertEquals(BackgroundJobStatus.PENDING, job.getStatus(), "attempt " + attempt);
            assertEquals(attempt, job.getAttempts());
            assertNotNull(job.getLastError());
            assertTrue(job.getLastError().contains("synthetic failure"));
            long delay = Duration.between(before, job.getRunAfter()).getSeconds();
            assertTrue(delay >= expectedBackoffSeconds[attempt - 1] - 2 && delay <= expectedBackoffSeconds[attempt - 1] + 2,
                    "attempt " + attempt + " backoff was " + delay + " s");

            assertEquals(0, worker.runOnce(), "not due before run_after");
            makeDue(job.getId());
        }

        assertEquals(1, worker.runOnce());
        BackgroundJob dead = only(TYPE, 9L);
        assertEquals(BackgroundJobStatus.DEAD, dead.getStatus());
        assertEquals(5, dead.getAttempts());
        assertEquals(5, handler.handled.size());
        assertEquals(List.of(9L), handler.deadLetters);
        assertEquals(1, jobService.countByStatus(BackgroundJobStatus.DEAD));

        makeDue(dead.getId());
        assertEquals(0, worker.runOnce(), "dead jobs are never picked up again");
        assertTrue(jobService.enqueue(TYPE, 9L), "a dead job does not block a new attempt");
    }

    @Test
    void jobRecoversAfterTransientFailures() {
        RecordingHandler handler = new RecordingHandler(2, false);
        BackgroundJobWorker worker = worker(handler);
        jobService.enqueue(TYPE, 11L);

        worker.runOnce();
        makeDue(only(TYPE, 11L).getId());
        worker.runOnce();
        makeDue(only(TYPE, 11L).getId());
        worker.runOnce();

        BackgroundJob job = only(TYPE, 11L);
        assertEquals(BackgroundJobStatus.DONE, job.getStatus());
        assertEquals(3, job.getAttempts());
        assertTrue(handler.deadLetters.isEmpty());
    }

    @Test
    void nonRetryableFailureAndMissingHandlerFailPermanently() {
        RecordingHandler handler = new RecordingHandler(Integer.MAX_VALUE, true);
        jobService.enqueue(TYPE, 13L);
        worker(handler).runOnce();
        assertEquals(BackgroundJobStatus.FAILED, only(TYPE, 13L).getStatus());

        jobService.enqueue(BackgroundJobType.PARSE_RESUME, 14L);
        new BackgroundJobWorker(jobService, List.of(), false, 5).runOnce();
        BackgroundJob unhandled = only(BackgroundJobType.PARSE_RESUME, 14L);
        assertEquals(BackgroundJobStatus.FAILED, unhandled.getStatus());
        assertTrue(unhandled.getLastError().contains("No handler"));
        assertEquals(2, jobService.countByStatus(BackgroundJobStatus.FAILED));
    }

    @Test
    void staleRunningJobsAreReleasedAndRunAgain() {
        RecordingHandler handler = new RecordingHandler(0, false);
        BackgroundJobWorker worker = worker(handler);
        jobService.enqueue(TYPE, 15L);
        Long id = only(TYPE, 15L).getId();
        jdbcTemplate.update("UPDATE background_jobs SET status = 'RUNNING', attempts = 1, locked_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11)), id);

        assertEquals(1, worker.runOnce());
        BackgroundJob job = only(TYPE, 15L);
        assertEquals(BackgroundJobStatus.DONE, job.getStatus());
        assertEquals(2, job.getAttempts());
    }

    @Test
    void staleJobWithExhaustedAttemptsIsDeadLettered() {
        RecordingHandler handler = new RecordingHandler(0, false);
        BackgroundJobWorker worker = worker(handler);
        jobService.enqueue(TYPE, 16L);
        Long id = only(TYPE, 16L).getId();
        jdbcTemplate.update("UPDATE background_jobs SET status = 'RUNNING', attempts = 5, locked_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30)), id);

        assertEquals(0, worker.runOnce());
        assertEquals(BackgroundJobStatus.DEAD, only(TYPE, 16L).getStatus());
        assertEquals(List.of(16L), handler.deadLetters);
        assertTrue(handler.handled.isEmpty());
    }

    @Test
    void backoffDoublesFromFifteenSecondsAndIsCappedAtThirtyMinutes() {
        assertEquals(Duration.ofSeconds(15), BackgroundJobService.backoffFor(0));
        assertEquals(Duration.ofSeconds(30), BackgroundJobService.backoffFor(1));
        assertEquals(Duration.ofSeconds(60), BackgroundJobService.backoffFor(2));
        assertEquals(Duration.ofSeconds(960), BackgroundJobService.backoffFor(6));
        assertEquals(Duration.ofMinutes(30), BackgroundJobService.backoffFor(7));
        assertEquals(Duration.ofMinutes(30), BackgroundJobService.backoffFor(40));
    }

    @Test
    void jobsAreClaimedOneAtATimeRightBeforeTheyRun() {
        jobService.enqueue(TYPE, 21L);
        jobService.enqueue(TYPE, 22L);
        List<String> statusesSeen = new ArrayList<>();
        BackgroundJobHandler handler = new BackgroundJobHandler() {
            @Override
            public BackgroundJobType type() {
                return TYPE;
            }

            @Override
            public void handle(long entityId) {
                // While one job runs, the other is still PENDING (not claimed and locked with the batch start time).
                long other = entityId == 21L ? 22L : 21L;
                statusesSeen.add(jdbcTemplate.queryForObject(
                        "SELECT status FROM background_jobs WHERE job_type = ? AND entity_id = ?", String.class,
                        TYPE.name(), other));
            }
        };
        assertEquals(2, worker(handler).runOnce());
        assertEquals(List.of("PENDING", "DONE"), statusesSeen);
        assertEquals(BackgroundJobStatus.DONE, only(TYPE, 21L).getStatus());
        assertEquals(BackgroundJobStatus.DONE, only(TYPE, 22L).getStatus());
    }

    @Test
    void aJobReleasedAsStaleIsNotMarkedDoneByTheSlowWorker() {
        jobService.enqueue(TYPE, 23L);
        BackgroundJob claimed = jobService.claimDue(1).get(0);
        jdbcTemplate.update("UPDATE background_jobs SET locked_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11)), claimed.getId());
        assertTrue(jobService.releaseStaleJobs().isEmpty(), "attempts left: back to PENDING, not dead");
        assertEquals(BackgroundJobStatus.PENDING, only(TYPE, 23L).getStatus());

        // The original worker finally finishes: the job stays queued for its re-run instead of being marked DONE.
        jobService.markDone(claimed.getId());
        jobService.markFailed(claimed.getId(), "late failure");
        BackgroundJob job = only(TYPE, 23L);
        assertEquals(BackgroundJobStatus.PENDING, job.getStatus());
        assertTrue(job.getLastError().contains("did not finish"));
        assertTrue(jobService.releaseStaleJobs().isEmpty(), "a released job is not released again");
    }

    @Test
    void duplicateHandlersForOneTypeAreRejected() {
        RecordingHandler first = new RecordingHandler(0, false);
        RecordingHandler second = new RecordingHandler(0, false);
        assertThrows(IllegalStateException.class, () -> new BackgroundJobWorker(jobService, List.of(first, second), false, 5));
    }

    private BackgroundJobWorker worker(BackgroundJobHandler handler) {
        return new BackgroundJobWorker(jobService, List.of(handler), false, 5);
    }

    private BackgroundJob only(BackgroundJobType type, long entityId) {
        List<BackgroundJob> jobs = jobRepository.findAllByJobTypeAndEntityIdOrderByIdAsc(type, entityId);
        assertFalse(jobs.isEmpty());
        return jobs.get(jobs.size() - 1);
    }

    private void makeDue(Long jobId) {
        jdbcTemplate.update("UPDATE background_jobs SET run_after = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)), jobId);
    }

    /** Fails the first {@code failures} calls (retryable, or permanently when {@code permanent}). */
    private static final class RecordingHandler implements BackgroundJobHandler {
        private final int failures;
        private final boolean permanent;
        private final AtomicInteger calls = new AtomicInteger();
        final List<Long> handled = new ArrayList<>();
        final List<Long> deadLetters = new ArrayList<>();

        RecordingHandler(int failures, boolean permanent) {
            this.failures = failures;
            this.permanent = permanent;
        }

        @Override
        public BackgroundJobType type() {
            return TYPE;
        }

        @Override
        public void handle(long entityId) {
            handled.add(entityId);
            if (calls.incrementAndGet() <= failures) {
                if (permanent) {
                    throw new NonRetryableJobException("synthetic permanent failure");
                }
                throw new IllegalStateException("synthetic failure " + calls.get());
            }
        }

        @Override
        public void onDeadLetter(long entityId, String lastError) {
            deadLetters.add(entityId);
        }
    }
}
