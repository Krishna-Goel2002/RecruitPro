package com.recruitpro.common.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioural anchors of a 1-4 rating scale (work-sample rubrics and interview questions, plan 5.6): a JSON object
 * keyed "1".."4". Helpers normalise key order and check completeness.
 */
public final class AnchorLevels {

    public static final List<String> LEVELS = List.of("1", "2", "3", "4");

    private AnchorLevels() {
    }

    /**
     * Returns the anchors keyed "1".."4" in that order, trimmed and cut to {@code maxLength}. A missing or blank level
     * gets {@code fallback} (which may be null); unknown keys are dropped.
     */
    public static Map<String, String> normalize(Map<String, String> anchors, String fallback, int maxLength) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String level : LEVELS) {
            String value = anchors == null ? null : anchors.get(level);
            String trimmed = value == null ? null : value.trim();
            if (trimmed == null || trimmed.isEmpty()) {
                result.put(level, fallback);
            } else {
                result.put(level, trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed);
            }
        }
        return result;
    }

    /** True when every level "1".."4" has non-blank text of at most {@code maxLength} characters. */
    public static boolean complete(Map<String, String> anchors, int maxLength) {
        if (anchors == null) {
            return false;
        }
        for (String level : LEVELS) {
            String value = anchors.get(level);
            if (value == null || value.isBlank() || value.trim().length() > maxLength) {
                return false;
            }
        }
        return true;
    }
}
