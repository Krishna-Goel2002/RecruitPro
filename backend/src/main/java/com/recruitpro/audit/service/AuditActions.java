package com.recruitpro.audit.service;

/**
 * Audit action names (Section 4, M10). Later packages add their own constants here.
 */
public final class AuditActions {

    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String INVITATION_CREATED = "INVITATION_CREATED";
    public static final String INVITATION_REVOKED = "INVITATION_REVOKED";
    public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";
    public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
    public static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";
    public static final String ADMIN_JOB_STATUS_OVERRIDE = "ADMIN_JOB_STATUS_OVERRIDE";
    public static final String JOB_REQUIREMENTS_CHANGED = "JOB_REQUIREMENTS_CHANGED";
    public static final String JOB_AUTO_CLOSED = "JOB_AUTO_CLOSED";
    public static final String APPLICATION_DECISION = "APPLICATION_DECISION";
    public static final String IDENTITY_REVEALED = "IDENTITY_REVEALED";
    public static final String RESUME_DOWNLOADED_BY_STAFF = "RESUME_DOWNLOADED_BY_STAFF";
    public static final String CLAIM_VERIFIED = "CLAIM_VERIFIED";
    public static final String WORK_SAMPLE_SCORED = "WORK_SAMPLE_SCORED";
    public static final String SCORECARD_SUBMITTED = "SCORECARD_SUBMITTED";
    public static final String AUDIT_EXPORTED = "AUDIT_EXPORTED";
    public static final String CANDIDATE_ACCOUNT_DELETED = "CANDIDATE_ACCOUNT_DELETED";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String REFRESH_TOKEN_REUSE_DETECTED = "REFRESH_TOKEN_REUSE_DETECTED";
    // WP-PLAT (plan v2, Section 6)
    public static final String MFA_ENABLED = "MFA_ENABLED";
    public static final String MFA_DISABLED = "MFA_DISABLED";
    public static final String MFA_RECOVERY_CODES_REGENERATED = "MFA_RECOVERY_CODES_REGENERATED";
    public static final String MFA_RECOVERY_CODE_USED = "MFA_RECOVERY_CODE_USED";
    public static final String MFA_VERIFICATION_FAILED = "MFA_VERIFICATION_FAILED";
    public static final String SSO_LOGIN = "SSO_LOGIN";
    public static final String SSO_IDENTITY_LINKED = "SSO_IDENTITY_LINKED";
    public static final String SSO_LOGIN_REFUSED = "SSO_LOGIN_REFUSED";
    public static final String WORK_SAMPLE_CANCELLED = "WORK_SAMPLE_CANCELLED";
    public static final String WORK_SAMPLE_FILE_DOWNLOADED = "WORK_SAMPLE_FILE_DOWNLOADED";
    public static final String SCORECARD_UPDATED = "SCORECARD_UPDATED";
    // AS-BE (HR assistant): a confirmed proposed action; details start with "via=ASSISTANT".
    public static final String ASSISTANT_ACTION_CONFIRMED = "ASSISTANT_ACTION_CONFIRMED";

    private AuditActions() {
    }
}
