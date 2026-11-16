package com.recruitpro.assessment.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.service.CandidateDepartureListener;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.common.time.UtcTime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * R2-M01: when the candidate withdraws (or deletes the account) the application's unfinished work samples (ASSIGNED or
 * STARTED) are cancelled with a reason, so they no longer show as open to HR or expire later as if the candidate had
 * run out of time. Submitted and scored assignments are kept as they are.
 */
@Component
@Order(10)
public class WorkSampleDepartureListener implements CandidateDepartureListener {

    static final String WITHDRAWN_REASON = "The candidate withdrew the application";
    static final String ACCOUNT_DELETED_REASON = "The candidate deleted their account";

    private final WorkSampleAssignmentRepository assignmentRepository;

    public WorkSampleDepartureListener(WorkSampleAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    public void candidateLeft(Application application, boolean accountDeleted) {
        LocalDateTime now = UtcTime.now();
        for (WorkSampleAssignment assignment : assignmentRepository.findAllForApplication(application.getId())) {
            if (assignment.getStatus() == WorkSampleAssignmentStatus.ASSIGNED
                    || assignment.getStatus() == WorkSampleAssignmentStatus.STARTED) {
                assignment.setStatus(WorkSampleAssignmentStatus.CANCELLED);
                assignment.setCancelledAt(now);
                assignment.setCancelledByUserId(null);
                assignment.setCancelReason(accountDeleted ? ACCOUNT_DELETED_REASON : WITHDRAWN_REASON);
                assignmentRepository.save(assignment);
            }
        }
    }
}
