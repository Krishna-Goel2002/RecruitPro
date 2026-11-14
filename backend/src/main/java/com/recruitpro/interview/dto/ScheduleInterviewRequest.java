package com.recruitpro.interview.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * POST /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews:
 * {interviewerUserId, scheduledAt (future, ISO-8601 with offset), durationMinutes 5..480, location? (≤ 500)}.
 */
public record ScheduleInterviewRequest(
        @NotNull Long interviewerUserId,
        @NotNull LocalDateTime scheduledAt,
        @NotNull @Min(5) @Max(480) Integer durationMinutes,
        @Size(max = 500) String location) {
}
