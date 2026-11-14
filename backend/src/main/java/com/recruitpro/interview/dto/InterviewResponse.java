package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.InterviewStatus;

import java.time.LocalDateTime;

/**
 * Interview (plan 5.6), staff view. {@code candidateName} follows blind review: the full name once the identity is
 * revealed, otherwise "Candidate #A-&lt;applicationId&gt;".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InterviewResponse(Long id, Long applicationId, Long jobId, String jobTitle, String candidateName,
                                Long interviewerUserId, String interviewerName, LocalDateTime scheduledAt,
                                int durationMinutes, String location, InterviewStatus status, ScorecardResponse scorecard) {
}
