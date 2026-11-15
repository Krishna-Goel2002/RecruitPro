package com.recruitpro.verification.entity;

/** Whether the candidate proved they own a source. Unproven sources yield at most PARTLY_VERIFIED. */
public enum OwnershipStatus {
    UNPROVEN, PENDING, PROVEN, FAILED
}
