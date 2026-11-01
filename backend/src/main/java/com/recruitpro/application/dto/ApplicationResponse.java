package com.recruitpro.application.dto;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import java.time.LocalDateTime;

public record ApplicationResponse(Long id, Long jobId, String jobTitle, Long resumeId, String resumeFilename,
        ApplicationStatus status, String coverLetter, LocalDateTime appliedAt, LocalDateTime updatedAt) {
    public static ApplicationResponse from(Application a) {
        return new ApplicationResponse(a.getId(), a.getJob().getId(), a.getJob().getTitle(), a.getResume().getId(),
                a.getResume().getOriginalFilename(), a.getStatus(), a.getCoverLetter(), a.getAppliedAt(), a.getUpdatedAt());
    }
}
