package com.recruitpro.jobs.service;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * D7 / M7: durable job queue. {@link #enqueue} joins the caller's transaction, so a job exists exactly when
 * the change that triggered it commits (outbox). Retries use exponential backoff:
 * {@code min(15 s x 2^attempts, 30 min)}; a job that reaches {@code max_attempts} becomes DEAD.
 */
@Service
public class BackgroundJobService {

    public static final Duration BASE_BACKOFF = Duration.ofSeconds(15);
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final Set<BackgroundJobStatus> ACTIVE = EnumSet.of(BackgroundJobStatus.PENDING, BackgroundJobStatus.RUNNING);
    private static final int MAX_ERROR_LENGTH = 500;

    private final BackgroundJobRepository repository;
    private final int defaultMaxAttempts;
    private final long staleAfterMinutes;

    public BackgroundJobService(BackgroundJobRepository repository,
                                @Value("${app.background-jobs.max-attempts:5}") int defaultMaxAttempts,
                                @Value("${app.background-jobs.stale-after-minutes:10}") long staleAfterMinutes) {
        this.repository = repository;
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
        this.staleAfterMinutes = Math.max(1, staleAfterMinutes);
    }

    /**
     * Queues work for an entity unless a PENDING or RUNNING job of the same type already exists for it.
     *
     * @return true when a new job was created
     */
    @Transactional
    public boolean enqueue(BackgroundJobType type, long entityId) {
        if (type == null) {
            throw new IllegalArgumentException("Background job type is required");
        }
        if (repository.existsByJobTypeAndEntityIdAndStatusIn(type, entityId, ACTIVE)) {
            return false;
        }
        BackgroundJob job = new BackgroundJob();
        job.setJobType(type);
        job.setEntityId(entityId);
        job.setStatus(BackgroundJobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(defaultMaxAttempts);
        job.setRunAfter(UtcTime.now());
        repository.save(job);
        return true;
    }

    /**
     * Like {@link #enqueue}, but only a PENDING job counts as a duplicate. A trigger that arrives while a job for
     * the entity is RUNNING therefore queues one follow-up run, so changes made during a run are not lost
     * (used by MATCH_APPLICATION triggers, whose handler reads its inputs when it starts).
     *
     * @return true when a new job was created
     */
    @Transactional
    public boolean enqueueFollowUp(BackgroundJobType type, long entityId) {
        if (type == null) {
            throw new IllegalArgumentException("Background job type is required");
        }
        if (repository.existsByJobTypeAndEntityIdAndStatusIn(type, entityId, EnumSet.of(BackgroundJobStatus.PENDING))) {
            return false;
        }
        BackgroundJob job = new BackgroundJob();
        job.setJobType(type);
        job.setEntityId(entityId);
        job.setStatus(BackgroundJobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(defaultMaxAttempts);
        job.setRunAfter(UtcTime.now());
        repository.save(job);
        return true;
    }

    /** Claims up to {@code limit} due jobs (PENDING with run_after in the past) with a conditional update. */
    @Transactional
    public List<BackgroundJob> claimDue(int limit) {
        LocalDateTime now = UtcTime.now();
        List<Long> dueIds = repository.findDueIds(BackgroundJobStatus.PENDING, now, PageRequest.of(0, Math.max(1, limit)));
        List<BackgroundJob> claimed = new ArrayList<>();
        for (Long id : dueIds) {
            if (repository.claim(id, BackgroundJobStatus.PENDING, BackgroundJobStatus.RUNNING, now) == 1) {
                repository.findById(id).ifPresent(claimed::add);
            }
        }
        return claimed;
    }

    @Transactional
    public void markDone(Long jobId) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(BackgroundJobStatus.DONE);
            job.setLockedAt(null);
            job.setLastError(null);
        });
    }

    /**
     * Records a failed attempt: DEAD when the attempt cap is reached, otherwise PENDING again after the
     * backoff delay. Returns the new status (null when the job no longer exists).
     */
    @Transactional
    public BackgroundJobStatus markAttemptFailed(Long jobId, String error) {
        BackgroundJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        if (job.getStatus() != BackgroundJobStatus.RUNNING) {
            return job.getStatus();
        }
        job.setLastError(truncate(error));
        job.setLockedAt(null);
        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(BackgroundJobStatus.DEAD);
        } else {
            job.setStatus(BackgroundJobStatus.PENDING);
            job.setRunAfter(UtcTime.now().plus(backoffFor(job.getAttempts())));
        }
        return job.getStatus();
    }

    /** Permanent failure without retry. */
    @Transactional
    public void markFailed(Long jobId, String error) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(BackgroundJobStatus.FAILED);
            job.setLockedAt(null);
            job.setLastError(truncate(error));
        });
    }

    /**
     * RUNNING jobs locked longer than {@code app.background-jobs.stale-after-minutes} (a crashed or stuck
     * worker) return to PENDING, or become DEAD when their attempts are exhausted.
     *
     * @return the jobs that became DEAD, for dead-letter callbacks
     */
    @Transactional
    public List<BackgroundJob> releaseStaleJobs() {
        LocalDateTime now = UtcTime.now();
        List<BackgroundJob> stale = repository.findAllByStatusAndLockedAtBefore(BackgroundJobStatus.RUNNING,
                now.minusMinutes(staleAfterMinutes));
        List<BackgroundJob> dead = new ArrayList<>();
        for (BackgroundJob job : stale) {
            job.setLockedAt(null);
            job.setLastError("Worker did not finish within " + staleAfterMinutes + " minutes");
            if (job.getAttempts() >= job.getMaxAttempts()) {
                job.setStatus(BackgroundJobStatus.DEAD);
                dead.add(job);
            } else {
                job.setStatus(BackgroundJobStatus.PENDING);
                job.setRunAfter(now);
            }
        }
        return dead;
    }

    @Transactional(readOnly = true)
    public long countByStatus(BackgroundJobStatus status) {
        return repository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public List<BackgroundJob> findJobs(BackgroundJobType type, long entityId) {
        return repository.findAllByJobTypeAndEntityIdOrderByIdAsc(type, entityId);
    }

    /** Delay before the next attempt after {@code attempts} failed attempts: min(15 s x 2^attempts, 30 min). */
    public static Duration backoffFor(int attempts) {
        int exponent = Math.max(0, Math.min(attempts, 20));
        long seconds = BASE_BACKOFF.getSeconds() * (1L << exponent);
        return seconds >= MAX_BACKOFF.getSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
