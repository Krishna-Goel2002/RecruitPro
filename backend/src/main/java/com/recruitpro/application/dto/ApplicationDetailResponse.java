package com.recruitpro.application.dto;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.hr.entity.RequirementType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ApplicationDetail (plan 5.5) = Application fields + status timeline + requirement verification + work samples
 * and interviews. {@code requirements} is null (omitted) when the organisation does not share verification status
 * or the application is no longer SUBMITTED, SHORTLISTED or INTERVIEWING; {@code verification} is VERIFIED
 * (matched at tier RESUME_STATED or higher), CLAIMED (matched only by the candidate's own claim) or NOT_VERIFIED.
 */
public record ApplicationDetailResponse(Long id, Long jobId, String jobTitle, String organizationName, Long resumeId,
                                        String resumeFilename, ApplicationStatus status, String coverLetter,
                                        LocalDateTime appliedAt, LocalDateTime updatedAt, LocalDateTime statusChangedAt,
                                        List<TimelineEntry> timeline, List<RequirementVerification> requirements,
                                        List<Object> workSamples, List<Object> interviews) {

    public static ApplicationDetailResponse of(ApplicationResponse base, List<TimelineEntry> timeline,
                                               List<RequirementVerification> requirements, List<Object> workSamples,
                                               List<Object> interviews) {
        return new ApplicationDetailResponse(base.id(), base.jobId(), base.jobTitle(), base.organizationName(),
                base.resumeId(), base.resumeFilename(), base.status(), base.coverLetter(), base.appliedAt(),
                base.updatedAt(), base.statusChangedAt(), timeline, requirements, workSamples, interviews);
    }

    public record TimelineEntry(ApplicationStatus status, LocalDateTime at) { }

    public record RequirementVerification(Long requirementId, RequirementType type, String description, boolean mandatory,
                                          String verification) { }
}
