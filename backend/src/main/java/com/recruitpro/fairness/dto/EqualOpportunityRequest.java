package com.recruitpro.fairness.dto;

/**
 * PUT /api/v1/candidates/me/equal-opportunity: {gender?, ageBand?, disability?, socialCategory?, consent: true}.
 * Values are the codes of plan 5.5 (null or absent: not answered); consent must be true.
 */
public record EqualOpportunityRequest(String gender, String ageBand, String disability, String socialCategory,
                                      Boolean consent) {
}
