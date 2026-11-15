package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** A current verdict on a ledger claim. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerdictSummary(Long id, String verifier, String verdict, String candidateStatus, String sourceLabel,
                             LocalDateTime createdAt) {
}
