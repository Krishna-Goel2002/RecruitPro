package com.recruitpro.verification.dto;

/** POST /api/v1/admin/verification/verdicts/{id}/label. */
public record VerdictLabelRequest(String labelVerdict, String note) {
}
