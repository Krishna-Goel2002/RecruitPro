package com.recruitpro.retention;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one retention run: rows deleted or anonymised per rule (in rule order) and the rules that failed
 * (their partial counts are not included; they are retried on the next run).
 */
public record RetentionReport(Map<String, Integer> counts, List<String> failed) {

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int count(String rule) {
        return counts.getOrDefault(rule, 0);
    }
}
