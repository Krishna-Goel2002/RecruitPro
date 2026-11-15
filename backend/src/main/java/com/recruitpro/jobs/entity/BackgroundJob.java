package com.recruitpro.jobs.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** D7 / M7: one unit of asynchronous work with retry, backoff and dead-lettering. */
@Entity
@Table(name = "background_jobs")
@Getter
@Setter
@NoArgsConstructor
public class BackgroundJob extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_type", nullable = false, length = 40)
    private BackgroundJobType jobType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private BackgroundJobStatus status = BackgroundJobStatus.PENDING;

    /** Number of times the job was claimed (incremented when a worker starts it). */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "run_after", nullable = false)
    private LocalDateTime runAfter;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * {@code <TYPE>:<entityId>} while the job is a freshly queued PENDING job, null otherwise (unique; WP-PLAT). A
     * concurrent duplicate enqueue fails on the unique constraint and becomes a no-op.
     */
    @Column(name = "dedupe_key", length = 120, unique = true)
    private String dedupeKey;
}
