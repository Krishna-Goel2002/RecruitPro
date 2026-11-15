package com.recruitpro.verification.dto;

/** POST /verdicts/{id}/response: {@code action} SHARE, HIDE or RECHECK; optional {@code note} (max 1000). */
public record VerdictResponseRequest(String action, String note) {
}
