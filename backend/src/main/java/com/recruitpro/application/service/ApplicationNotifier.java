package com.recruitpro.application.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Candidate notifications (D13): APPLICATION_RECEIVED on submission and APPLICATION_STATUS_CHANGED on every
 * status change. Written to the outbox in the caller's transaction.
 */
@Component
public class ApplicationNotifier {

    private final NotificationService notificationService;
    private final OrganizationRepository organizationRepository;

    public ApplicationNotifier(NotificationService notificationService, OrganizationRepository organizationRepository) {
        this.notificationService = notificationService;
        this.organizationRepository = organizationRepository;
    }

    public void applicationReceived(Application application) {
        User user = recipient(application);
        if (user == null) {
            return;
        }
        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.APPLICATION_RECEIVED,
                baseParams(user, application.getJob()));
    }

    public void statusChanged(Application application, ApplicationStatus status) {
        User user = recipient(application);
        if (user == null || status == null) {
            return;
        }
        Map<String, String> params = baseParams(user, application.getJob());
        params.put("status", statusLabel(status));
        params.put("message", message(status));
        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.APPLICATION_STATUS_CHANGED, params);
    }

    /** Candidate-facing status label. */
    public static String statusLabel(ApplicationStatus status) {
        return switch (status) {
            case SUBMITTED -> "Under review";
            case SHORTLISTED -> "Shortlisted";
            case INTERVIEWING -> "Interviewing";
            case SELECTED -> "Selected";
            case REJECTED -> "Not selected";
            case WITHDRAWN -> "Withdrawn";
        };
    }

    static String message(ApplicationStatus status) {
        return switch (status) {
            case SUBMITTED -> "Your application is being reviewed again.";
            case SHORTLISTED -> "You have been shortlisted. The hiring team may contact you about next steps.";
            case INTERVIEWING -> "You are moving to the interview stage. You will receive interview details separately.";
            case SELECTED -> "Congratulations, you have been selected. The hiring team will contact you about next steps.";
            case REJECTED -> "The hiring team has decided not to move forward with your application at this time. Thank you for your interest.";
            case WITHDRAWN -> "Your application has been withdrawn.";
        };
    }

    private User recipient(Application application) {
        if (application == null || application.getCandidate() == null) {
            return null;
        }
        User user = application.getCandidate().getUser();
        if (user == null || user.getDeletedAt() != null || user.getEmail() == null || user.getEmail().isBlank()) {
            return null;
        }
        return user;
    }

    private Map<String, String> baseParams(User user, Job job) {
        Map<String, String> params = new HashMap<>();
        params.put("firstName", user.getFirstName() == null ? "" : user.getFirstName());
        params.put("jobTitle", job == null ? "" : job.getTitle());
        String organizationName = job == null || job.getOrganizationId() == null ? null
                : organizationRepository.findById(job.getOrganizationId()).map(Organization::getName).orElse(null);
        params.put("organizationName", organizationName == null ? "the organisation" : organizationName);
        return params;
    }
}
