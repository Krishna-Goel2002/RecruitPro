package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.Recommendation;

import java.time.LocalDateTime;
import java.util.List;

/** Scorecard (plan 5.6). Staff only; never part of a candidate view. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScorecardResponse(List<ScorecardRating> ratings, Recommendation recommendation, String summary,
                                LocalDateTime submittedAt) {
}
