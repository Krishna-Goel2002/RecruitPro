package com.recruitpro.verification.service;

/** Audit action names of the verification package (plan v2 section 5). */
public final class VerificationAuditActions {

    public static final String SOURCE_CONNECTED = "VERIFICATION_SOURCE_CONNECTED";
    public static final String SOURCE_REVOKED = "VERIFICATION_SOURCE_REVOKED";
    public static final String OWNERSHIP_PROVEN = "VERIFICATION_OWNERSHIP_PROVEN";
    public static final String VERDICT_SHARED = "VERDICT_SHARED";
    public static final String VERDICT_REVIEWED = "VERDICT_REVIEWED";
    public static final String SETTINGS_CHANGED = "VERIFICATION_SETTINGS_CHANGED";
    public static final String REFERENCE_REQUESTED = "REFERENCE_REQUESTED";

    private VerificationAuditActions() {
    }
}
