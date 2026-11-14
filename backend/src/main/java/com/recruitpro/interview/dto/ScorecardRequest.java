package com.recruitpro.interview.dto;

import com.recruitpro.interview.entity.Recommendation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/v1/interviews/{id}/scorecard: Scorecard minus {@code submittedAt}. Ratings 0..100, each with a unique
 * questionId (≤ 40), rating 1-4, notes ≤ 2000 and requirementId null or a requirement of the job; summary ≤ 4000.
 */
public record ScorecardRequest(
        @NotNull @Size(max = 100) List<ScorecardRating> ratings,
        @NotNull Recommendation recommendation,
        @Size(max = 4000) String summary) {
}
