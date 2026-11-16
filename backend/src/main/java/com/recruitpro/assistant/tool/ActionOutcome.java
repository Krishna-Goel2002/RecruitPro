package com.recruitpro.assistant.tool;

/**
 * Result of a confirmed action: {@code kind} (job, application, interview, work_sample, work_sample_assignment,
 * interview_kit), the id of what was created or changed, a portal {@code link}, the confirmation {@code message}, and
 * the audit target and details (no personal data).
 */
public record ActionOutcome(String kind, Long id, String link, String message, String auditTargetType,
                            String auditTargetId, String auditDetails) {
}
