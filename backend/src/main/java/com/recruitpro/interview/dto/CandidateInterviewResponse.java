package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.InterviewStatus;

import java.time.LocalDateTime;

/**
 * The candidate's view of an interview (plan 5.5): {id, jobTitle, scheduledAt, durationMinutes, location, status}.
 * No interviewer, ratings, notes or recommendation. {@code applicationId} (integration pass, R5#8): the application
 * the interview belongs to.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateInterviewResponse(Long id, String jobTitle, LocalDateTime scheduledAt, int durationMinutes,
                                         String location, InterviewStatus status, Long applicationId) {
}
