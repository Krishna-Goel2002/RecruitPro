package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One claim of the candidate's Claim Ledger with its current verdicts (GET /ledger). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LedgerClaim(String subjectType, Long subjectId, String claimType, String value, String tier,
                          List<VerdictSummary> verdicts) {
}
