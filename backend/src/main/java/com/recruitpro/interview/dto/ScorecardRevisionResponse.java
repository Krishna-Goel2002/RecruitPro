package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** One replaced version of a scorecard: who replaced it, when, and the version as it was before the edit. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScorecardRevisionResponse(Long id, Long revisedByUserId, String revisedByName, LocalDateTime revisedAt,
                                        ScorecardResponse previous) {
}
