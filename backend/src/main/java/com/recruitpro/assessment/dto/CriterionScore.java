package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One rubric score (1-4) with an optional note; also the stored form inside {@code scores_json}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CriterionScore(Long requirementId, String criterion, Integer score, String note) {
}
