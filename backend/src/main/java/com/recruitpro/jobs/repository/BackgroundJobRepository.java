package com.recruitpro.jobs.repository;

import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, Long> {

    boolean existsByJobTypeAndEntityIdAndStatusIn(BackgroundJobType jobType, Long entityId,
                                                  Collection<BackgroundJobStatus> statuses);

    @Query("select j.id from BackgroundJob j where j.status = :status and j.runAfter <= :now order by j.runAfter asc, j.id asc")
    List<Long> findDueIds(@Param("status") BackgroundJobStatus status, @Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Conditional claim: only one worker can move a given job from PENDING to RUNNING. The dedupe key is released, so
     * a trigger arriving during the run can queue a follow-up job.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BackgroundJob j set j.status = :running, j.attempts = j.attempts + 1, j.lockedAt = :now, j.updatedAt = :now, "
            + "j.dedupeKey = null where j.id = :id and j.status = :pending")
    int claim(@Param("id") Long id, @Param("pending") BackgroundJobStatus pending,
              @Param("running") BackgroundJobStatus running, @Param("now") LocalDateTime now);

    List<BackgroundJob> findAllByStatusAndLockedAtBefore(BackgroundJobStatus status, LocalDateTime cutoff);

    /**
     * Conditional release of one stale RUNNING job: only succeeds while the job is still RUNNING with a lock older than
     * {@code cutoff}, so two instances releasing at the same time cannot both act on it (and a job that another worker
     * re-claimed meanwhile, with a fresh lock, is left alone).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BackgroundJob j set j.status = :status, j.lockedAt = null, j.lastError = :error, j.runAfter = :runAfter, "
            + "j.updatedAt = :now where j.id = :id and j.status = :running and j.lockedAt < :cutoff")
    int releaseStale(@Param("id") Long id, @Param("running") BackgroundJobStatus running,
                     @Param("cutoff") LocalDateTime cutoff, @Param("status") BackgroundJobStatus status,
                     @Param("error") String error, @Param("runAfter") LocalDateTime runAfter,
                     @Param("now") LocalDateTime now);

    long countByStatus(BackgroundJobStatus status);

    Optional<BackgroundJob> findByDedupeKey(String dedupeKey);

    List<BackgroundJob> findAllByJobTypeAndEntityIdAndStatusInOrderByIdAsc(BackgroundJobType jobType, Long entityId,
                                                                          Collection<BackgroundJobStatus> statuses);

    List<BackgroundJob> findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType jobType, Long entityId);

    /** Most recent job of the type for each of the entities (applicant lists show "matching..." or failures). */
    @Query("select new com.recruitpro.jobs.repository.LatestJobStatus(j.entityId, j.status) from BackgroundJob j "
            + "where j.jobType = :type and j.id in (select max(j2.id) from BackgroundJob j2 "
            + "where j2.jobType = :type and j2.entityId in :entityIds group by j2.entityId)")
    List<LatestJobStatus> findLatestStatuses(@Param("type") BackgroundJobType type,
                                             @Param("entityIds") Collection<Long> entityIds);
}
