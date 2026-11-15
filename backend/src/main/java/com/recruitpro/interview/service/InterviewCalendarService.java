package com.recruitpro.interview.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.notification.service.NotificationAttachment;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICS invitations for interview e-mails (WP-PLAT). One event per interview with a stable UID
 * ({@code interview-<id>@<mail-from domain>}) and the interview's {@code icsSequence}; ORGANIZER is
 * {@code app.mail.from}. The candidate's copy lists the candidate and the interviewer as attendees; the interviewer's
 * copy lists the candidate only once the identity is revealed (blind review: before that the interviewer sees the
 * blind label only).
 */
@Component
public class InterviewCalendarService {

    private static final Pattern NAME_ADDRESS = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$");

    private final OrganizationRepository organizationRepository;
    private final IcsCalendar.Person organizer;
    private final String uidDomain;

    public InterviewCalendarService(OrganizationRepository organizationRepository,
                                    @Value("${app.mail.from:RecruitPro <no-reply@recruitpro.local>}") String mailFrom) {
        this.organizationRepository = organizationRepository;
        this.organizer = parseAddress(mailFrom);
        String email = organizer.email();
        int at = email == null ? -1 : email.lastIndexOf('@');
        this.uidDomain = at < 0 ? "recruitpro.local" : email.substring(at + 1);
    }

    /** Invitation for the candidate (METHOD:REQUEST or CANCEL). */
    public NotificationAttachment forCandidate(Interview interview, Application application, Job job, User candidate,
                                               User interviewer, IcsCalendar.Method method) {
        List<IcsCalendar.Person> attendees = new ArrayList<>(2);
        attendees.add(person(candidate));
        if (interviewer != null) {
            attendees.add(person(interviewer));
        }
        String summary = "Interview: " + job.getTitle();
        String description = "Interview for " + job.getTitle() + organizationSuffix(job) + ", " + interview.getDurationMinutes()
                + " minutes.";
        return attachment(interview, method, summary, description, attendees);
    }

    /** Invitation for the interviewer; the candidate is an attendee only after the identity was revealed. */
    public NotificationAttachment forInterviewer(Interview interview, Application application, Job job, User candidate,
                                                 User interviewer, String link, IcsCalendar.Method method) {
        List<IcsCalendar.Person> attendees = new ArrayList<>(2);
        attendees.add(person(interviewer));
        boolean revealed = isRevealed(application, job);
        if (revealed && candidate != null) {
            attendees.add(person(candidate));
        }
        String label = revealed && candidate != null && !candidate.getFullName().isBlank() ? candidate.getFullName()
                : ApplicantQueryService.blindName(application.getId());
        String summary = "Interview: " + label + " - " + job.getTitle();
        String description = "Structured interview for " + job.getTitle() + ". Kit and scorecard: " + (link == null ? "" : link);
        return attachment(interview, method, summary, description, attendees);
    }

    String uid(Interview interview) {
        return "interview-" + interview.getId() + "@" + uidDomain;
    }

    private NotificationAttachment attachment(Interview interview, IcsCalendar.Method method, String summary,
                                              String description, List<IcsCalendar.Person> attendees) {
        String ics = IcsCalendar.build(new IcsCalendar.Event(method, uid(interview), interview.getIcsSequence(),
                interview.getScheduledAt(), interview.getDurationMinutes(), summary, description, interview.getLocation(),
                organizer, attendees, Instant.now()));
        return new NotificationAttachment(method == IcsCalendar.Method.CANCEL ? "cancel.ics" : "invite.ics",
                IcsCalendar.contentType(method), ics);
    }

    private boolean isRevealed(Application application, Job job) {
        boolean blind = job.getOrganizationId() == null || organizationRepository.findById(job.getOrganizationId())
                .map(Organization::isBlindReview).orElse(true);
        return ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), blind);
    }

    private String organizationSuffix(Job job) {
        if (job.getOrganizationId() == null) {
            return "";
        }
        return organizationRepository.findById(job.getOrganizationId()).map(org -> " at " + org.getName()).orElse("");
    }

    private static IcsCalendar.Person person(User user) {
        return user == null ? null : new IcsCalendar.Person(user.getFullName(), user.getEmail());
    }

    static IcsCalendar.Person parseAddress(String value) {
        if (value == null || value.isBlank()) {
            return new IcsCalendar.Person("RecruitPro", "no-reply@recruitpro.local");
        }
        Matcher matcher = NAME_ADDRESS.matcher(value);
        if (matcher.matches()) {
            String name = matcher.group(1).trim();
            return new IcsCalendar.Person(name.isEmpty() ? null : name, matcher.group(2).trim());
        }
        return new IcsCalendar.Person(null, value.trim());
    }
}
