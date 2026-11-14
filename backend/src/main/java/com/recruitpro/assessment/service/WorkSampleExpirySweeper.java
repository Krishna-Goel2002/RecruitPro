package com.recruitpro.assessment.service;

import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.common.time.UtcTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Marks work-sample assignments EXPIRED (plan 5.6): STARTED ones whose time limit plus the 5-minute grace has
 * passed, and ASSIGNED ones whose due date has passed. Each change is a conditional update, so a submission that
 * holds the row lock wins. Runs every {@code app.work-samples.expiry-sweep.interval-ms} (default 60 s) when
 * {@code app.work-samples.expiry-sweep.enabled} is true.
 */
@Component
public class WorkSampleExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(WorkSampleExpirySweeper.class);

    private final WorkSampleAssignmentRepository assignmentRepository;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;

    public WorkSampleExpirySweeper(WorkSampleAssignmentRepository assignmentRepository,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.work-samples.expiry-sweep.enabled:true}") boolean enabled) {
        this.assignmentRepository = assignmentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.work-samples.expiry-sweep.interval-ms:60000}",
            initialDelayString = "${app.work-samples.expiry-sweep.initial-delay-ms:45000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int expired = expireOverdue();
            if (expired > 0) {
                log.info("Marked {} work-sample assignment(s) as expired", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("Work-sample expiry sweep failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Expires every overdue assignment now; returns how many changed. */
    public int expireOverdue() {
        LocalDateTime now = UtcTime.now();
        Integer expired = transactionTemplate.execute(status -> {
            List<Long> overStarted = new ArrayList<>();
            for (WorkSampleAssignment assignment : assignmentRepository.findStartedWithSample(WorkSampleAssignmentStatus.STARTED)) {
                if (now.isAfter(CandidateWorkSampleService.submissionDeadline(assignment))) {
                    overStarted.add(assignment.getId());
                }
            }
            List<Long> overdueAssigned = assignmentRepository.findIdsDueBefore(WorkSampleAssignmentStatus.ASSIGNED, now);
            int changed = 0;
            for (Long id : overStarted) {
                changed += assignmentRepository.transition(id, WorkSampleAssignmentStatus.STARTED,
                        WorkSampleAssignmentStatus.EXPIRED, now);
            }
            for (Long id : overdueAssigned) {
                changed += assignmentRepository.transition(id, WorkSampleAssignmentStatus.ASSIGNED,
                        WorkSampleAssignmentStatus.EXPIRED, now);
            }
            return changed;
        });
        return expired == null ? 0 : expired;
    }
}
