package com.recruitpro.candidate.entity;

/** EMAIL, PHONE and LINK are identity data: shown only to the candidate, never sent to matching. */
public enum ResumeFactType {
    SKILL,
    EDUCATION,
    EXPERIENCE,
    CERTIFICATION,
    EMAIL,
    PHONE,
    LINK;

    public boolean isContact() {
        return this == EMAIL || this == PHONE || this == LINK;
    }
}
