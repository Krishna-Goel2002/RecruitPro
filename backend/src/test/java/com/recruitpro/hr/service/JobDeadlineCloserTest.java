package com.recruitpro.hr.service;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R2-L07: a job that cannot be closed does not keep the jobs after it open. */
class JobDeadlineCloserTest {

    @Test
    void oneFailingJobDoesNotStopTheOthersFromClosing() {
        JobRepository jobs = mock(JobRepository.class);
        AuditService audit = mock(AuditService.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(jobs.findIdsByStatusAndDeadlineAtOrBefore(eq(JobStatus.PUBLISHED), any())).thenReturn(List.of(1L, 2L));
        when(jobs.lockById(1L)).thenThrow(new PessimisticLockingFailureException("row is locked"));
        Job expired = new Job();
        expired.setId(2L);
        expired.setOrganizationId(7L);
        expired.setTitle("Analyst");
        expired.setStatus(JobStatus.PUBLISHED);
        expired.setDeadline(UtcTime.now().minusHours(1));
        when(jobs.lockById(2L)).thenReturn(Optional.of(expired));

        int closed = new JobDeadlineCloser(jobs, audit, transactions, true).closeExpiredJobs();

        assertEquals(1, closed);
        assertEquals(JobStatus.CLOSED, expired.getStatus());
        assertEquals(JobDeadlineCloser.DEADLINE_PASSED, expired.getClosedReason());
    }
}
