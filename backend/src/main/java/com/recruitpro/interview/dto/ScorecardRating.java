package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One scorecard rating (1-4) of a kit question; also the stored form inside {@code ratings_json}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScorecardRating(String questionId, Long requirementId, Integer rating, String notes) {
}
