package com.recruitpro.application.dto;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import java.time.LocalDateTime;

/** Application (plan 5.5): the candidate's own view. */
public record ApplicationResponse(Long id, Long jobId, String jobTitle, String organizationName, Long resumeId,
        String resumeFilename, ApplicationStatus status, String coverLetter, LocalDateTime appliedAt,
        LocalDateTime updatedAt, LocalDateTime statusChangedAt) {
    public static ApplicationResponse from(Application a, String organizationName) {
        return new ApplicationResponse(a.getId(), a.getJob().getId(), a.getJob().getTitle(), organizationName,
                a.getResume().getId(), a.getResume().getOriginalFilename(), a.getStatus(), a.getCoverLetter(),
                a.getAppliedAt(), a.getUpdatedAt(), a.getStatusChangedAt());
    }
}
