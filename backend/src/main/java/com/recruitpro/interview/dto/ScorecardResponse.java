package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.interview.entity.Recommendation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scorecard (plan 5.6). Staff only; never part of a candidate view. WP-PLAT added {@code id}, {@code updatedAt} (later
 * than {@code submittedAt} after an edit) and {@code revisionCount} (number of earlier versions kept).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScorecardResponse(Long id, List<ScorecardRating> ratings, Recommendation recommendation, String summary,
                                LocalDateTime submittedAt, LocalDateTime updatedAt, int revisionCount) {
}
