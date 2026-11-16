package com.recruitpro.interview.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.service.CandidateDepartureListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * R2-M01: when the candidate withdraws (or deletes the account) the application's scheduled interviews are cancelled
 * and the interviewer is told, instead of staying SCHEDULED in "my interviews" and in everybody's calendar.
 */
@Component
@Order(20)
public class InterviewDepartureListener implements CandidateDepartureListener {

    private final InterviewService interviewService;

    public InterviewDepartureListener(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @Override
    public void candidateLeft(Application application, boolean accountDeleted) {
        interviewService.cancelScheduledForDeparture(application, accountDeleted);
    }
}
