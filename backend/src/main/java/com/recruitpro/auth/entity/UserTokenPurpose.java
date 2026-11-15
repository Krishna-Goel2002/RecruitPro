package com.recruitpro.auth.entity;

public enum UserTokenPurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD,
    /** Second sign-in step of a staff user with MFA enabled (5 minutes, single use; WP-PLAT). */
    MFA_LOGIN,
    /** First MFA enrolment of a staff user whose organisation requires MFA (5 minutes; consumed by confirm). */
    MFA_ENROLL
}
