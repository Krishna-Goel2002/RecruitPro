package com.recruitpro.verification.entity;

import java.util.Locale;

/**
 * The five verdict classes (plan v2, 3.1). {@code NOT_FOUND} is neutral: it never lowers a score or rank.
 */
public enum VerdictValue {
    VERIFIED,
    PARTLY_VERIFIED,
    NOT_FOUND,
    CONTRADICTED,
    NOT_CHECKABLE;

    /** Ownership cap (principle 2): without proven ownership a verdict is at most PARTLY_VERIFIED. */
    public VerdictValue capped(boolean ownershipProven) {
        return this == VERIFIED && !ownershipProven ? PARTLY_VERIFIED : this;
    }

    /** Parses a verdict name; null for null, blank or unknown values. */
    public static VerdictValue parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return VerdictValue.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
