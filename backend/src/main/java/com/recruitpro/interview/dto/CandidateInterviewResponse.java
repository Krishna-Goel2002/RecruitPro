package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.InterviewStatus;

import java.time.LocalDateTime;

/**
 * The candidate's view of an interview (plan 5.5): {id, jobTitle, scheduledAt, durationMinutes, location, status}.
 * No interviewer, ratings, notes or recommendation.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateInterviewResponse(Long id, String jobTitle, LocalDateTime scheduledAt, int durationMinutes,
                                         String location, InterviewStatus status) {
}
