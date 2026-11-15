package com.recruitpro.jobs.entity;

/** Background job types (Section 3.3; plan v2 Section 3.5). The entity id meaning depends on the type. */
public enum BackgroundJobType {
    /** entity id = resume_analyses.id */
    PARSE_RESUME,
    /** entity id = applications.id (handler provided by WP-BE-B) */
    MATCH_APPLICATION,
    /** entity id = verification_runs.id (handler provided by WP-VBE) */
    VERIFY_SOURCE,
    /** entity id = verification_reference_requests.id, after the referee answered (handler provided by WP-VBE) */
    VERIFY_REFERENCE
}
