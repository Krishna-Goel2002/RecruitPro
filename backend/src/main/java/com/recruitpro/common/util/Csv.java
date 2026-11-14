package com.recruitpro.common.util;

import java.math.BigDecimal;
import java.util.List;

/**
 * RFC 4180 CSV cells that are safe to open in spreadsheet programs. Text cells are always quoted (inner quotes
 * doubled), and a value whose first character, or first character after leading whitespace, is {@code = + - @},
 * a tab, CR or LF is prefixed with an apostrophe so that it is never evaluated as a formula (CSV injection, OWASP).
 * Rows end with CRLF.
 */
public final class Csv {

    public static final String LINE_END = "\r\n";

    private Csv() {
    }

    /** A quoted, formula-neutralised text cell; empty for null. */
    public static String text(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (startsLikeFormula(safe) || startsLikeFormula(safe.stripLeading())) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    /** A plain number cell; empty for null. */
    public static String number(Number value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    /** A boolean cell ({@code true}/{@code false}); empty for null. */
    public static String bool(Boolean value) {
        return value == null ? "" : value.toString();
    }

    /** Joins already-encoded cells into one row including the line end. */
    public static String row(List<String> cells) {
        return String.join(",", cells) + LINE_END;
    }

    static boolean startsLikeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n';
    }
}
