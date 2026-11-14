package com.recruitpro.notification.entity;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text notification templates (D13). Placeholders use {@code {{name}}}; missing values render empty.
 * {@code inApp} controls whether registered recipients also get an inbox copy (never for messages that
 * carry one-time links).
 */
public enum NotificationTemplate {

    VERIFY_EMAIL("Confirm your email address for RecruitPro", """
            Hello {{firstName}},

            Please confirm your email address by opening this link within 24 hours:
            {{link}}

            If you did not create a RecruitPro account, you can ignore this message.
            """, false),

    PASSWORD_RESET("Reset your RecruitPro password", """
            Hello {{firstName}},

            We received a request to reset your password. Open this link within {{expiresInMinutes}} minutes to choose a new one:
            {{link}}

            If you did not ask for this, you can ignore this message; your password stays the same.
            """, false),

    INVITATION("You are invited to join {{organizationName}} on RecruitPro", """
            Hello,

            {{inviterName}} invited you to join {{organizationName}} on RecruitPro as {{role}}.
            Accept the invitation before {{expiresAt}} (UTC):
            {{link}}

            If you were not expecting this invitation, you can ignore this message.
            """, false),

    APPLICATION_RECEIVED("We received your application for {{jobTitle}}", """
            Hello {{firstName}},

            Thank you for applying for {{jobTitle}} at {{organizationName}}. We will let you know when the status of your application changes.
            """, true),

    APPLICATION_STATUS_CHANGED("Update on your application for {{jobTitle}}", """
            Hello {{firstName}},

            The status of your application for {{jobTitle}} at {{organizationName}} is now: {{status}}.
            {{message}}
            """, true),

    WORK_SAMPLE_ASSIGNED("A work sample is waiting for you: {{title}}", """
            Hello {{firstName}},

            As part of your application for {{jobTitle}}, please complete the work sample "{{title}}".
            Due: {{dueAt}}
            {{link}}
            """, true),

    INTERVIEW_SCHEDULED("Interview scheduled for {{jobTitle}}", """
            Hello {{firstName}},

            Your interview for {{jobTitle}} is scheduled for {{scheduledAt}} (UTC) and lasts {{durationMinutes}} minutes.
            Location: {{location}}
            """, true),

    INTERVIEW_ASSIGNED("You are interviewing {{candidateLabel}} for {{jobTitle}}", """
            Hello {{firstName}},

            You have been assigned to interview {{candidateLabel}} for {{jobTitle}} on {{scheduledAt}} (UTC).
            {{link}}
            """, true),

    INTERVIEW_CANCELLED("Interview cancelled: {{jobTitle}}", """
            Hello {{firstName}},

            The interview for {{jobTitle}} planned for {{scheduledAt}} (UTC) has been cancelled.
            {{message}}
            """, true);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    private final String subjectTemplate;
    private final String bodyTemplate;
    private final boolean inApp;

    NotificationTemplate(String subjectTemplate, String bodyTemplate, boolean inApp) {
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
        this.inApp = inApp;
    }

    public boolean isInApp() {
        return inApp;
    }

    /** Subject line; values are flattened to one line (no header injection), max 255 characters. */
    public String renderSubject(Map<String, String> params) {
        String subject = render(subjectTemplate, params, true).trim();
        return subject.length() > 255 ? subject.substring(0, 255) : subject;
    }

    public String renderBody(Map<String, String> params) {
        return render(bodyTemplate, params, false).strip() + "\n";
    }

    private static String render(String template, Map<String, String> params, boolean singleLine) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            String value = params == null ? null : params.get(matcher.group(1));
            if (value == null) {
                value = "";
            }
            if (singleLine) {
                value = value.replace('\r', ' ').replace('\n', ' ');
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
