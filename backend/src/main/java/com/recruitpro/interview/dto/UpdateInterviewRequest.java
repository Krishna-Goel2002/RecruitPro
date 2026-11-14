package com.recruitpro.interview.dto;

import com.recruitpro.interview.entity.InterviewStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * PATCH /api/v1/interviews/{id}: {scheduledAt?, durationMinutes?, location?, status?: 'CANCELLED'}. Absent (null)
 * fields stay unchanged; an empty location clears it. With {@code status: CANCELLED} the other fields are ignored.
 */
public record UpdateInterviewRequest(
        LocalDateTime scheduledAt,
        @Min(5) @Max(480) Integer durationMinutes,
        @Size(max = 500) String location,
        InterviewStatus status) {
}
