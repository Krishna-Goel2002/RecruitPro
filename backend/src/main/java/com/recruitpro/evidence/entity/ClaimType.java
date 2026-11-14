package com.recruitpro.evidence.entity;

import com.recruitpro.candidate.entity.ResumeFactType;

public enum ClaimType {
    SKILL, CERTIFICATION, EDUCATION, EXPERIENCE, OTHER;

    /** Claim type for a candidate correction of a resume fact; null for contact facts (no claim). */
    public static ClaimType fromFactType(ResumeFactType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case SKILL -> SKILL;
            case CERTIFICATION -> CERTIFICATION;
            case EDUCATION -> EDUCATION;
            case EXPERIENCE -> EXPERIENCE;
            case EMAIL, PHONE, LINK -> null;
        };
    }
}
