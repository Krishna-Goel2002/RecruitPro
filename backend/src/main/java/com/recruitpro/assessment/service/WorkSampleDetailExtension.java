package com.recruitpro.assessment.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.service.ApplicationDetailExtension;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.AssignmentStatusRow;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Work samples in the applicant views (WP-BE-B extension point): HR list {@code workSampleStatus} (latest
 * assignment that is not CANCELLED, else CANCELLED), HR detail {@code WorkSampleAssignment[]} with responses and
 * scores, and the candidate's {@code CandidateWorkSample[]} without scores or rubric. Read-only.
 */
@Component
@Order(10)
public class WorkSampleDetailExtension implements ApplicationDetailExtension {

    private final WorkSampleAssignmentRepository assignmentRepository;
    private final WorkSampleViews views;

    public WorkSampleDetailExtension(WorkSampleAssignmentRepository assignmentRepository, WorkSampleViews views) {
        this.assignmentRepository = assignmentRepository;
        this.views = views;
    }

    @Override
    public Map<Long, ApplicationDetailExtension.ApplicantExtras> applicantExtras(Collection<Long> applicationIds) {
        Map<Long, ApplicationDetailExtension.ApplicantExtras> extras = new HashMap<>();
        if (applicationIds == null || applicationIds.isEmpty()) {
            return extras;
        }
        // Rows come oldest first, so the last non-cancelled assignment wins.
        Map<Long, WorkSampleAssignmentStatus> latest = new HashMap<>();
        for (AssignmentStatusRow row : assignmentRepository.findStatusesByApplicationIds(applicationIds)) {
            WorkSampleAssignmentStatus current = latest.get(row.applicationId());
            if (row.status() != WorkSampleAssignmentStatus.CANCELLED || current == null
                    || current == WorkSampleAssignmentStatus.CANCELLED) {
                latest.put(row.applicationId(), row.status());
            }
        }
        latest.forEach((applicationId, status) ->
                extras.put(applicationId, new ApplicationDetailExtension.ApplicantExtras(status.name(), 0)));
        return extras;
    }

    @Override
    public List<?> staffWorkSamples(Application application) {
        return views.assignments(assignmentRepository.findAllForApplication(application.getId()));
    }

    @Override
    public List<?> candidateWorkSamples(Application application) {
        return assignmentRepository.findAllForApplication(application.getId()).stream().map(views::candidateView).toList();
    }
}
