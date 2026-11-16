package com.recruitpro.interview.dto;

import com.recruitpro.application.entity.ApplicationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.InterviewStatus;

import java.time.LocalDateTime;

/**
 * Interview (plan 5.6), staff view. {@code candidateName} follows blind review: the full name once the identity is
 * revealed, otherwise "Candidate #A-&lt;applicationId&gt;". Integration pass (R6-B4): {@code applicationStatus} and
 * {@code scorecardEditable} (a scorecard can be submitted, or the existing one edited, in the current interview and
 * application state; only the assigned interviewer may do it, which the portal checks with {@code interviewerUserId}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InterviewResponse(Long id, Long applicationId, Long jobId, String jobTitle, String candidateName,
                                Long interviewerUserId, String interviewerName, LocalDateTime scheduledAt,
                                int durationMinutes, String location, InterviewStatus status, ScorecardResponse scorecard,
                                ApplicationStatus applicationStatus, boolean scorecardEditable) {
}
