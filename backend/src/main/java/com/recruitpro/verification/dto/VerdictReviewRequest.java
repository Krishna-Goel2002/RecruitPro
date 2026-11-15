package com.recruitpro.verification.dto;

/** POST /api/v1/hr/verdicts/{id}/review: {@code decision} CONFIRM or OVERRULE; a note is required to overrule. */
public record VerdictReviewRequest(String decision, String note) {
}
