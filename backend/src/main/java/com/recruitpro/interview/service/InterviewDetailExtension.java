package com.recruitpro.interview.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.service.ApplicationDetailExtension;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewCountRow;
import com.recruitpro.interview.repository.InterviewRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interviews in the applicant views (WP-BE-B extension point): HR list {@code interviewCount} (not cancelled), HR
 * detail {@code Interview[]} with scorecards, and the candidate's {id, jobTitle, scheduledAt, durationMinutes,
 * location, status}[] without interviewer, ratings or notes. Read-only.
 */
@Component
@Order(20)
public class InterviewDetailExtension implements ApplicationDetailExtension {

    private final InterviewRepository interviewRepository;
    private final InterviewViews views;

    public InterviewDetailExtension(InterviewRepository interviewRepository, InterviewViews views) {
        this.interviewRepository = interviewRepository;
        this.views = views;
    }

    @Override
    public Map<Long, ApplicationDetailExtension.ApplicantExtras> applicantExtras(Collection<Long> applicationIds) {
        Map<Long, ApplicationDetailExtension.ApplicantExtras> extras = new HashMap<>();
        if (applicationIds == null || applicationIds.isEmpty()) {
            return extras;
        }
        for (InterviewCountRow row : interviewRepository.countByApplicationIds(applicationIds, InterviewStatus.CANCELLED)) {
            int count = row.interviews() == null ? 0 : row.interviews().intValue();
            extras.put(row.applicationId(), new ApplicationDetailExtension.ApplicantExtras(null, count));
        }
        return extras;
    }

    @Override
    public List<?> staffInterviews(Application application) {
        return views.staffViews(interviewRepository.findAllForApplication(application.getId()));
    }

    @Override
    public List<?> candidateInterviews(Application application) {
        return interviewRepository.findAllForApplication(application.getId()).stream().map(views::candidateView).toList();
    }
}
