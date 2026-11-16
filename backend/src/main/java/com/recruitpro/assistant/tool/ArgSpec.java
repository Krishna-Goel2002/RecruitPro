package com.recruitpro.assistant.tool;

import java.util.List;

/**
 * One argument of a tool: name, type, whether it is required, allowed values (ENUM), limits (string length, list
 * size or integer range) and a short hint. {@link #describe()} is the catalogue text, e.g. "integer, required".
 */
public record ArgSpec(String name, ArgType type, boolean required, List<String> values, long min, long max, String hint) {

    public static ArgSpec required(String name, ArgType type) {
        return new ArgSpec(name, type, true, List.of(), Long.MIN_VALUE, Long.MAX_VALUE, null);
    }

    public static ArgSpec optional(String name, ArgType type) {
        return new ArgSpec(name, type, false, List.of(), Long.MIN_VALUE, Long.MAX_VALUE, null);
    }

    public ArgSpec oneOf(List<String> allowed) {
        return new ArgSpec(name, type, required, List.copyOf(allowed), min, max, hint);
    }

    public ArgSpec range(long minimum, long maximum) {
        return new ArgSpec(name, type, required, values, minimum, maximum, hint);
    }

    /** Maximum length of a STRING, number of items of a list. */
    public ArgSpec maxLength(int maximum) {
        return new ArgSpec(name, type, required, values, min, maximum, hint);
    }

    public ArgSpec hint(String text) {
        return new ArgSpec(name, type, required, values, min, max, text);
    }

    /** Catalogue text: "type, required|optional[, one of A|B][, hint]". */
    public String describe() {
        StringBuilder text = new StringBuilder(type.label()).append(required ? ", required" : ", optional");
        if (!values.isEmpty()) {
            text.append(", one of ").append(String.join("|", values));
        }
        if (type == ArgType.INTEGER && (min != Long.MIN_VALUE || max != Long.MAX_VALUE)) {
            text.append(", ").append(min == Long.MIN_VALUE ? "" : min).append("..").append(max == Long.MAX_VALUE ? "" : max);
        }
        if (hint != null && !hint.isBlank()) {
            text.append(", ").append(hint);
        }
        return text.toString();
    }
}
