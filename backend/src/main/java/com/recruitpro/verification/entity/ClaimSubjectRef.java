package com.recruitpro.verification.entity;

/**
 * A verdict subject as an agent claim id: {@code fact:<resumeAnalysisFactId>} or {@code claim:<candidateClaimId>}.
 */
public record ClaimSubjectRef(SubjectType type, long id) {

    public static final String FACT_PREFIX = "fact:";
    public static final String CLAIM_PREFIX = "claim:";

    public static String of(SubjectType type, Long id) {
        if (type == null || id == null) {
            return null;
        }
        return (type == SubjectType.RESUME_FACT ? FACT_PREFIX : CLAIM_PREFIX) + id;
    }

    @Override
    public String toString() {
        return of(type, id);
    }

    /** Parses {@code fact:12} or {@code claim:7}; null for anything else. */
    public static ClaimSubjectRef parse(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        SubjectType type;
        String digits;
        if (text.startsWith(FACT_PREFIX)) {
            type = SubjectType.RESUME_FACT;
            digits = text.substring(FACT_PREFIX.length());
        } else if (text.startsWith(CLAIM_PREFIX)) {
            type = SubjectType.CANDIDATE_CLAIM;
            digits = text.substring(CLAIM_PREFIX.length());
        } else {
            return null;
        }
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return new ClaimSubjectRef(type, Long.parseLong(digits));
    }
}
