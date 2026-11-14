package com.recruitpro.common.util;

import java.util.Locale;

/**
 * L4: user input placed in SQL LIKE patterns is escaped so that {@code %}, {@code _} and {@code \} match
 * literally. Use with {@code cb.like(expression, LikePatterns.containsLower(text), LikePatterns.ESCAPE)}.
 */
public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private LikePatterns() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** {@code %text%} for a case-insensitive contains match against a {@code lower(...)} expression. */
    public static String containsLower(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "%" + escape(value) + "%";
    }
}
