package com.recruitpro.verification.service;

import com.recruitpro.verification.dto.VerificationMetricsResponse;
import com.recruitpro.verification.entity.VerdictValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shadow-evaluation metrics (plan v2 section 5). Pure: every pair is (verdict produced, gold label of one labeller).
 *
 * <ul>
 *   <li>agreement = pairs where both match / pairs;</li>
 *   <li>false-corroboration rate = predicted VERIFIED among pairs whose gold label is not VERIFIED (the agent-service
 *       eval uses the same definition); undefined (null) without such pairs;</li>
 *   <li>Cohen's kappa over the 5 verdict classes: (p_o - p_e) / (1 - p_e) with p_e = sum over classes of
 *       (predicted share x gold share); 1.0 when p_e = 1 and every pair agrees, otherwise null when p_e = 1.</li>
 * </ul>
 */
public final class VerificationMetrics {

    public static final int READY_MIN_LABELS = 150;
    public static final double READY_MAX_FCR = 0.02;
    public static final double READY_MIN_KAPPA = 0.70;

    private VerificationMetrics() {
    }

    /** One labelled verdict. */
    public record Pair(String verifier, VerdictValue predicted, VerdictValue gold) {
    }

    public static VerificationMetricsResponse compute(List<Pair> pairs) {
        List<Pair> usable = new ArrayList<>();
        for (Pair pair : pairs == null ? List.<Pair>of() : pairs) {
            if (pair != null && pair.predicted() != null && pair.gold() != null) {
                usable.add(pair);
            }
        }
        Summary overall = summarise(usable);
        Map<String, List<Pair>> byVerifier = new TreeMap<>();
        for (Pair pair : usable) {
            byVerifier.computeIfAbsent(pair.verifier() == null ? "unknown" : pair.verifier(), k -> new ArrayList<>()).add(pair);
        }
        List<VerificationMetricsResponse.VerifierMetrics> verifiers = new ArrayList<>();
        for (Map.Entry<String, List<Pair>> entry : byVerifier.entrySet()) {
            Summary summary = summarise(entry.getValue());
            verifiers.add(new VerificationMetricsResponse.VerifierMetrics(entry.getKey(), summary.labelled,
                    summary.agreement, summary.fcr, summary.kappa));
        }
        List<VerificationMetricsResponse.VerdictClassMetrics> classes = new ArrayList<>();
        for (VerdictValue value : VerdictValue.values()) {
            int predicted = 0;
            int gold = 0;
            int agreed = 0;
            for (Pair pair : usable) {
                if (pair.predicted() == value) {
                    predicted++;
                }
                if (pair.gold() == value) {
                    gold++;
                }
                if (pair.predicted() == value && pair.gold() == value) {
                    agreed++;
                }
            }
            classes.add(new VerificationMetricsResponse.VerdictClassMetrics(value.name(), predicted, gold, agreed,
                    predicted == 0 ? null : round((double) agreed / predicted),
                    gold == 0 ? null : round((double) agreed / gold)));
        }
        // R3-B12: the thresholds are compared with the exact values, not the 4-place rounded ones shown (an FCR of
        // 0.02004 is reported as 0.02 but is above the 0.02 limit).
        boolean ready = overall.labelled >= READY_MIN_LABELS
                && overall.rawFcr != null && overall.rawFcr <= READY_MAX_FCR
                && overall.rawKappa != null && overall.rawKappa >= READY_MIN_KAPPA;
        return new VerificationMetricsResponse(overall.labelled, overall.agreement, overall.fcr, overall.kappa,
                verifiers, classes, ready);
    }

    /** Cohen's kappa over the five verdict classes; null when undefined (no pairs, or chance agreement 1 with disagreement). */
    public static Double kappa(List<Pair> pairs) {
        Double raw = rawKappa(pairs);
        return raw == null ? null : round(raw);
    }

    /** Cohen's kappa, unrounded; null when undefined. */
    static Double rawKappa(List<Pair> pairs) {
        int n = pairs.size();
        if (n == 0) {
            return null;
        }
        Map<VerdictValue, Integer> predictedCounts = new LinkedHashMap<>();
        Map<VerdictValue, Integer> goldCounts = new LinkedHashMap<>();
        int agreed = 0;
        for (Pair pair : pairs) {
            predictedCounts.merge(pair.predicted(), 1, Integer::sum);
            goldCounts.merge(pair.gold(), 1, Integer::sum);
            if (pair.predicted() == pair.gold()) {
                agreed++;
            }
        }
        double observed = (double) agreed / n;
        double expected = 0.0;
        for (VerdictValue value : VerdictValue.values()) {
            double p = predictedCounts.getOrDefault(value, 0) / (double) n;
            double g = goldCounts.getOrDefault(value, 0) / (double) n;
            expected += p * g;
        }
        if (Math.abs(1.0 - expected) < 1e-12) {
            return agreed == n ? 1.0 : null;
        }
        return (observed - expected) / (1.0 - expected);
    }

    private static Summary summarise(List<Pair> pairs) {
        Summary summary = new Summary();
        summary.labelled = pairs.size();
        if (pairs.isEmpty()) {
            return summary;
        }
        int agreed = 0;
        int goldNotVerified = 0;
        int falseCorroborations = 0;
        for (Pair pair : pairs) {
            if (pair.predicted() == pair.gold()) {
                agreed++;
            }
            if (pair.gold() != VerdictValue.VERIFIED) {
                goldNotVerified++;
                if (pair.predicted() == VerdictValue.VERIFIED) {
                    falseCorroborations++;
                }
            }
        }
        summary.agreement = round((double) agreed / pairs.size());
        summary.rawFcr = goldNotVerified == 0 ? null : (double) falseCorroborations / goldNotVerified;
        summary.fcr = summary.rawFcr == null ? null : round(summary.rawFcr);
        summary.rawKappa = rawKappa(pairs);
        summary.kappa = summary.rawKappa == null ? null : round(summary.rawKappa);
        return summary;
    }

    static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class Summary {
        int labelled;
        Double agreement;
        Double fcr;
        Double kappa;
        Double rawFcr;
        Double rawKappa;
    }
}
