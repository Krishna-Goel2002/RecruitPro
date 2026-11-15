package com.recruitpro.verification;

import com.recruitpro.verification.dto.VerificationMetricsResponse;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.service.VerificationMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.recruitpro.verification.entity.VerdictValue.CONTRADICTED;
import static com.recruitpro.verification.entity.VerdictValue.NOT_CHECKABLE;
import static com.recruitpro.verification.entity.VerdictValue.NOT_FOUND;
import static com.recruitpro.verification.entity.VerdictValue.PARTLY_VERIFIED;
import static com.recruitpro.verification.entity.VerdictValue.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cohen's kappa over the five verdict classes, false-corroboration rate and the VISIBLE readiness rule. */
class VerificationMetricsTest {

    private static VerificationMetrics.Pair pair(VerdictValue predicted, VerdictValue gold) {
        return new VerificationMetrics.Pair("code", predicted, gold);
    }

    private static List<VerificationMetrics.Pair> repeat(int times, VerdictValue predicted, VerdictValue gold) {
        List<VerificationMetrics.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            pairs.add(pair(predicted, gold));
        }
        return pairs;
    }

    @Test
    void kappaMatchesAHandComputedExample() {
        // 50 pairs: 20 VERIFIED/VERIFIED, 5 VERIFIED/PARTLY, 10 PARTLY/PARTLY, 5 NOT_FOUND/PARTLY, 10 NOT_FOUND/NOT_FOUND.
        List<VerificationMetrics.Pair> pairs = new ArrayList<>();
        pairs.addAll(repeat(20, VERIFIED, VERIFIED));
        pairs.addAll(repeat(5, VERIFIED, PARTLY_VERIFIED));
        pairs.addAll(repeat(10, PARTLY_VERIFIED, PARTLY_VERIFIED));
        pairs.addAll(repeat(5, NOT_FOUND, PARTLY_VERIFIED));
        pairs.addAll(repeat(10, NOT_FOUND, NOT_FOUND));
        // p_o = 40/50 = 0.8; predicted shares V .5, P .2, N .3; gold shares V .4, P .4, N .2;
        // p_e = .5*.4 + .2*.4 + .3*.2 = .34; kappa = (.8 - .34) / (1 - .34) = 0.69697
        assertEquals(0.697, VerificationMetrics.kappa(pairs), 0.0005);
        VerificationMetricsResponse metrics = VerificationMetrics.compute(pairs);
        assertEquals(50, metrics.labelled());
        assertEquals(0.8, metrics.agreement(), 1e-9);
        // gold not VERIFIED = 30, of which predicted VERIFIED = 5
        assertEquals(5.0 / 30.0, metrics.falseCorroborationRate(), 0.0001);
        VerificationMetricsResponse.VerdictClassMetrics verified = metrics.byVerdict().get(0);
        assertEquals("VERIFIED", verified.verdict());
        assertEquals(25, verified.predicted());
        assertEquals(20, verified.gold());
        assertEquals(20, verified.agreed());
        assertEquals(0.8, verified.precision(), 1e-9);
        assertEquals(1.0, verified.recall(), 1e-9);
        assertNull(metrics.byVerdict().get(3).precision(), "CONTRADICTED was never predicted");
        assertFalse(metrics.readyForVisible());
    }

    @Test
    void perfectAgreementIsKappaOneAndChanceAgreementIsZero() {
        assertEquals(1.0, VerificationMetrics.kappa(List.of(pair(VERIFIED, VERIFIED), pair(NOT_FOUND, NOT_FOUND),
                pair(CONTRADICTED, CONTRADICTED), pair(NOT_CHECKABLE, NOT_CHECKABLE))), 1e-9);
        assertEquals(1.0, VerificationMetrics.kappa(List.of(pair(VERIFIED, VERIFIED), pair(VERIFIED, VERIFIED))), 1e-9,
                "a single class agreed on everywhere");
        assertEquals(0.0, VerificationMetrics.kappa(List.of(pair(VERIFIED, VERIFIED), pair(VERIFIED, NOT_FOUND),
                pair(NOT_FOUND, VERIFIED), pair(NOT_FOUND, NOT_FOUND))), 1e-9);
        assertNull(VerificationMetrics.kappa(List.of()));
        VerificationMetricsResponse empty = VerificationMetrics.compute(List.of());
        assertEquals(0, empty.labelled());
        assertNull(empty.agreement());
        assertNull(empty.falseCorroborationRate());
        assertNull(empty.kappa());
        assertEquals(5, empty.byVerdict().size());
        assertTrue(empty.byVerifier().isEmpty());
        assertFalse(empty.readyForVisible());
    }

    @Test
    void readinessNeeds150LabelsLowFalseCorroborationAndKappa() {
        List<VerificationMetrics.Pair> pairs = new ArrayList<>();
        pairs.addAll(repeat(60, VERIFIED, VERIFIED));
        pairs.addAll(repeat(45, PARTLY_VERIFIED, PARTLY_VERIFIED));
        pairs.addAll(repeat(44, NOT_FOUND, NOT_FOUND));
        pairs.add(pair(VERIFIED, NOT_FOUND)); // 1 false corroboration of 90 gold non-VERIFIED: 0.011
        VerificationMetricsResponse ready = VerificationMetrics.compute(pairs);
        assertEquals(150, ready.labelled());
        assertTrue(ready.falseCorroborationRate() <= 0.02);
        assertTrue(ready.kappa() >= 0.70);
        assertTrue(ready.readyForVisible());

        pairs.addAll(repeat(2, VERIFIED, PARTLY_VERIFIED)); // 3 of 92 = 0.033 > 0.02
        assertFalse(VerificationMetrics.compute(pairs).readyForVisible());
        assertFalse(VerificationMetrics.compute(pairs.subList(0, 149)).readyForVisible(), "fewer than 150 labels");
    }

    @Test
    void metricsAreSplitByVerifier() {
        List<VerificationMetrics.Pair> pairs = List.of(new VerificationMetrics.Pair("web", VERIFIED, VERIFIED),
                new VerificationMetrics.Pair("code", PARTLY_VERIFIED, NOT_FOUND), new VerificationMetrics.Pair("code", NOT_FOUND, NOT_FOUND));
        VerificationMetricsResponse metrics = VerificationMetrics.compute(pairs);
        assertEquals(2, metrics.byVerifier().size());
        assertEquals("code", metrics.byVerifier().get(0).verifier());
        assertEquals(2, metrics.byVerifier().get(0).labelled());
        assertEquals(0.5, metrics.byVerifier().get(0).agreement(), 1e-9);
        assertEquals(0.0, metrics.byVerifier().get(0).falseCorroborationRate(), 1e-9);
        assertNull(metrics.byVerifier().get(1).falseCorroborationRate(), "web has no gold non-VERIFIED labels");
    }
}
