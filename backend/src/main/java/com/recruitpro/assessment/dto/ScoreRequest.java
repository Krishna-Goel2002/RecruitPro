package com.recruitpro.assessment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/v1/hr/work-sample-assignments/{id}/score: one entry per rubric criterion (matched by criterion text),
 * score 1-4, note up to 2000 characters. The requirement link is taken from the stored rubric, not from the request.
 */
public record ScoreRequest(@NotNull @Size(min = 1, max = 30) List<CriterionScore> scores) {
}
