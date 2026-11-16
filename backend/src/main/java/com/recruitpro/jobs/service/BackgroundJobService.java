package com.recruitpro.jobs.service;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * D7 / M7: durable job queue. {@link #enqueue} joins the caller's transaction, so a job exists exactly when
 * the change that triggered it commits (outbox). Retries use exponential backoff:
 * {@code min(15 s x 2^attempts, 30 min)}; attempts are counted when a job is claimed, so the first retry waits 30 s.
 * A job that reaches {@code max_attempts} becomes DEAD.
 */
@Service
public class BackgroundJobService {

    public static final Duration BASE_BACKOFF = Duration.ofSeconds(15);
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final Set<BackgroundJobStatus> ACTIVE = EnumSet.of(BackgroundJobStatus.PENDING, BackgroundJobStatus.RUNNING);
    private static final int MAX_ERROR_LENGTH = 500;

    private final BackgroundJobRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final int defaultMaxAttempts;
    private final long staleAfterMinutes;

    public BackgroundJobService(BackgroundJobRepository repository, JdbcTemplate jdbcTemplate, DataSource dataSource,
                                @Value("${app.background-jobs.max-attempts:5}") int defaultMaxAttempts,
                                @Value("${app.background-jobs.stale-after-minutes:10}") long staleAfterMinutes) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
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
        return enqueueJob(type, entityId, ACTIVE).created();
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
        return enqueueJob(type, entityId, EnumSet.of(BackgroundJobStatus.PENDING)).created();
    }

    /**
     * Queues a job unless one in {@code duplicateStatuses} exists; returns the new job or the existing one
     * ({@code created=false}). Multi-instance safe (WP-PLAT): a new PENDING job carries the unique dedupe key
     * {@code <TYPE>:<entityId>}, so two instances enqueueing at the same moment cannot both insert one; the loser's
     * insert is rolled back to a savepoint (the caller's transaction stays usable on PostgreSQL) and the winner's job
     * is returned.
     */
    @Transactional
    public EnqueueResult enqueueJob(BackgroundJobType type, long entityId, Set<BackgroundJobStatus> duplicateStatuses) {
        if (type == null) {
            throw new IllegalArgumentException("Background job type is required");
        }
        String key = dedupeKey(type, entityId);
        BackgroundJob pending = repository.findByDedupeKey(key).orElse(null);
        if (pending != null) {
            if (pending.getStatus() == BackgroundJobStatus.PENDING) {
                return new EnqueueResult(pending, false);
            }
            // The key belongs to PENDING jobs only (claim clears it). A row that left PENDING another way (operator SQL,
            // a manual dead-letter) must not block new work for the entity: release its key.
            repository.clearDedupeKey(pending.getId());
        }
        // Jobs waiting for a retry and RUNNING jobs carry no key; they are found by status.
        List<BackgroundJob> active = repository.findAllByJobTypeAndEntityIdAndStatusInOrderByIdAsc(type, entityId,
                duplicateStatuses == null || duplicateStatuses.isEmpty() ? ACTIVE : duplicateStatuses);
        if (!active.isEmpty()) {
            return new EnqueueResult(active.get(0), false);
        }
        Long id = insertPending(type, entityId, key);
        if (id == null) {
            return new EnqueueResult(repository.findByDedupeKey(key).orElse(null), false);
        }
        return new EnqueueResult(repository.findById(id).orElse(null), true);
    }

    /** {@code <TYPE>:<entityId>}. */
    public static String dedupeKey(BackgroundJobType type, long entityId) {
        return type.name() + ":" + entityId;
    }

    /**
     * Inserts a PENDING job with its dedupe key inside a savepoint of the current transaction. Returns the new id, or
     * null when another transaction holds the key (unique violation, rolled back to the savepoint).
     */
    public Long insertPending(BackgroundJobType type, long entityId, String key) {
        LocalDateTime now = UtcTime.now();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = null;
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive() && !connection.getAutoCommit()) {
                savepoint = connection.setSavepoint();
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement statement = con.prepareStatement("INSERT INTO background_jobs (job_type, entity_id, "
                        + "status, attempts, max_attempts, run_after, dedupe_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?)", new String[] {"id"});
                statement.setString(1, type.name());
                statement.setLong(2, entityId);
                statement.setString(3, BackgroundJobStatus.PENDING.name());
                statement.setInt(4, defaultMaxAttempts);
                statement.setTimestamp(5, Timestamp.valueOf(now));
                statement.setString(6, key);
                statement.setTimestamp(7, Timestamp.valueOf(now));
                statement.setTimestamp(8, Timestamp.valueOf(now));
                return statement;
            }, keyHolder);
            if (savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
            Number id = keyHolder.getKey();
            return id == null ? null : id.longValue();
        } catch (DataIntegrityViolationException ex) {
            if (savepoint != null) {
                try {
                    connection.rollback(savepoint);
                } catch (SQLException rollbackFailure) {
                    throw new IllegalStateException("Could not roll back to the enqueue savepoint", rollbackFailure);
                }
            }
            if (ex instanceof DuplicateKeyException) {
                // Another transaction holds the dedupe key: the duplicate enqueue is a no-op.
                return null;
            }
            throw ex;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not enqueue a background job", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
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

    /**
     * Marks a RUNNING job DONE. A job that is no longer RUNNING (released as stale and re-queued, or dead-lettered,
     * while this worker was still busy) is left as it is.
     */
    @Transactional
    public void markDone(Long jobId) {
        repository.findById(jobId).filter(job -> job.getStatus() == BackgroundJobStatus.RUNNING).ifPresent(job -> {
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

    /** Permanent failure without retry (RUNNING jobs only, like {@link #markDone}). */
    @Transactional
    public void markFailed(Long jobId, String error) {
        repository.findById(jobId).filter(job -> job.getStatus() == BackgroundJobStatus.RUNNING).ifPresent(job -> {
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
        LocalDateTime cutoff = now.minusMinutes(staleAfterMinutes);
        List<BackgroundJob> stale = repository.findAllByStatusAndLockedAtBefore(BackgroundJobStatus.RUNNING, cutoff);
        List<BackgroundJob> dead = new ArrayList<>();
        String error = "Worker did not finish within " + staleAfterMinutes + " minutes";
        for (BackgroundJob job : stale) {
            BackgroundJobStatus next = job.getAttempts() >= job.getMaxAttempts()
                    ? BackgroundJobStatus.DEAD : BackgroundJobStatus.PENDING;
            // Conditional update: another instance may release (or a worker re-claim) the same job concurrently; only
            // the instance whose update succeeds releases it and runs the dead-letter callback.
            int released = repository.releaseStale(job.getId(), BackgroundJobStatus.RUNNING, cutoff, next, error,
                    next == BackgroundJobStatus.PENDING ? now : job.getRunAfter(), now);
            if (released == 1 && next == BackgroundJobStatus.DEAD) {
                job.setStatus(BackgroundJobStatus.DEAD);
                job.setLockedAt(null);
                job.setLastError(error);
                dead.add(job);
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

    /** The queued job (or the existing duplicate) and whether this call created it. */
    public record EnqueueResult(BackgroundJob job, boolean created) {
    }
}
