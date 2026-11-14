package com.recruitpro.evidence.entity;

/**
 * Evidence Ladder (D5). The ordinal is the tier: SELF_CLAIMED (0) &lt; RESUME_STATED (1) &lt; CORROBORATED (2)
 * &lt; DEMONSTRATED (3) &lt; INTERVIEW_VERIFIED (4). Ranking uses verified coverage, i.e. tier &gt;= 1.
 */
public enum EvidenceTier {
    SELF_CLAIMED,
    RESUME_STATED,
    CORROBORATED,
    DEMONSTRATED,
    INTERVIEW_VERIFIED;

    public int level() {
        return ordinal();
    }

    /** Tier 1 or higher counts towards verified coverage. */
    public boolean isVerified() {
        return ordinal() >= RESUME_STATED.ordinal();
    }

    /** Parses a tier name; null for null, blank or unknown values. */
    public static EvidenceTier parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EvidenceTier.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
