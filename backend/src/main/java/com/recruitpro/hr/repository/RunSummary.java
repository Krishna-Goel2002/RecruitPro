package com.recruitpro.hr.repository;

import java.math.BigDecimal;

/** Numbers of a current match run used for ranking (no JSON). */
public record RunSummary(Long applicationId, Long runId, BigDecimal verifiedCoverage, BigDecimal claimedCoverage,
                         Boolean knockoutsVerified) {
}
