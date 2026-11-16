package com.recruitpro.hr.service;

import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes PUBLISHED jobs whose application deadline has passed (plan 3.4): status CLOSED, closed_reason
 * DEADLINE_PASSED, audited as JOB_AUTO_CLOSED by SYSTEM. Runs every {@code app.jobs.deadline-closer.interval-ms}
 * (default 60 s) when {@code app.jobs.deadline-closer.enabled} is true; each job is closed in its own transaction
 * with its row locked. ON_HOLD jobs are left alone (their deadline can still be extended before re-publishing).
 */
@Component
public class JobDeadlineCloser {

    public static final String DEADLINE_PASSED = "DEADLINE_PASSED";
    private static final Logger log = LoggerFactory.getLogger(JobDeadlineCloser.class);

    private final JobRepository jobRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;

    public JobDeadlineCloser(JobRepository jobRepository, AuditService auditService,
                             PlatformTransactionManager transactionManager,
                             @Value("${app.jobs.deadline-closer.enabled:true}") boolean enabled) {
        this.jobRepository = jobRepository;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.jobs.deadline-closer.interval-ms:60000}",
            initialDelayString = "${app.jobs.deadline-closer.initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int closed = closeExpiredJobs();
            if (closed > 0) {
                log.info("Closed {} job(s) whose application deadline passed", closed);
            }
        } catch (RuntimeException ex) {
            log.warn("Deadline closer run failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Closes every PUBLISHED job whose deadline is at or before now; returns how many were closed. */
    public int closeExpiredJobs() {
        LocalDateTime now = UtcTime.now();
        List<Long> ids = transactionTemplate.execute(status ->
                jobRepository.findIdsByStatusAndDeadlineAtOrBefore(JobStatus.PUBLISHED, now));
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Long id : ids) {
            try {
                Boolean done = transactionTemplate.execute(status -> closeOne(id, now));
                if (Boolean.TRUE.equals(done)) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                // R2-L07: one job that cannot be closed (lock time-out, failing audit insert) must not keep every
                // later job open; it is retried on the next run.
                log.warn("Could not close job {} after its deadline: {}", id, ex.getClass().getSimpleName());
            }
        }
        return closed;
    }

    private boolean closeOne(Long id, LocalDateTime now) {
        Job job = jobRepository.lockById(id).orElse(null);
        if (job == null || job.getStatus() != JobStatus.PUBLISHED || job.getDeadline() == null || job.getDeadline().isAfter(now)) {
            return false;
        }
        job.transitionTo(JobStatus.CLOSED);
        job.setClosedReason(DEADLINE_PASSED);
        auditService.recordFor(job.getOrganizationId(), null, "SYSTEM", AuditActions.JOB_AUTO_CLOSED, "JOB",
                String.valueOf(job.getId()), "Job #" + job.getId() + " (" + job.getTitle() + ") closed: the application deadline "
                        + UtcTime.format(job.getDeadline()) + " passed");
        return true;
    }
}
