package com.recruitpro.interview.dto;

import com.recruitpro.application.entity.ApplicationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.hr.dto.RequirementEvidenceResponse;
import com.recruitpro.interview.entity.InterviewStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * InterviewDetail = Interview + {@code kit} (the job's interview questions, [] when there is no kit) +
 * {@code focusRequirements} (RequirementEvidence of the current match run, requirements without verified evidence
 * first: unverified knockouts, then must-haves, then the rest; verified ones last, weakest tier first).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InterviewDetailResponse(Long id, Long applicationId, Long jobId, String jobTitle, String candidateName,
                                      Long interviewerUserId, String interviewerName, LocalDateTime scheduledAt,
                                      int durationMinutes, String location, InterviewStatus status,
                                      ScorecardResponse scorecard, List<InterviewQuestion> kit,
                                      List<RequirementEvidenceResponse> focusRequirements,
                                      ApplicationStatus applicationStatus, boolean scorecardEditable) {

    public static InterviewDetailResponse of(InterviewResponse base, List<InterviewQuestion> kit,
                                             List<RequirementEvidenceResponse> focusRequirements) {
        return new InterviewDetailResponse(base.id(), base.applicationId(), base.jobId(), base.jobTitle(),
                base.candidateName(), base.interviewerUserId(), base.interviewerName(), base.scheduledAt(),
                base.durationMinutes(), base.location(), base.status(), base.scorecard(), kit, focusRequirements,
                base.applicationStatus(), base.scorecardEditable());
    }
}
