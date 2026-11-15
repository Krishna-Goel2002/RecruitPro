package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * GET /api/v1/admin/verification/metrics. Rates are 0..1 and null when undefined (no labels, or no gold
 * non-VERIFIED labels for the false-corroboration rate). {@code readyForVisible}: labelled &gt;= 150,
 * falseCorroborationRate &lt;= 0.02 and kappa &gt;= 0.70.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerificationMetricsResponse(int labelled, Double agreement, Double falseCorroborationRate, Double kappa,
                                          List<VerifierMetrics> byVerifier, List<VerdictClassMetrics> byVerdict,
                                          boolean readyForVisible) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VerifierMetrics(String verifier, int labelled, Double agreement, Double falseCorroborationRate,
                                  Double kappa) {
    }

    /** {@code predicted}: verdicts of this class; {@code gold}: labels of this class; {@code agreed}: both. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VerdictClassMetrics(String verdict, int predicted, int gold, int agreed, Double precision, Double recall) {
    }
}
