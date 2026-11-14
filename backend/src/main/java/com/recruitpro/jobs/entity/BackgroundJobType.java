package com.recruitpro.jobs.entity;

/** Background job types (Section 3.3). The entity id meaning depends on the type. */
public enum BackgroundJobType {
    /** entity id = resume_analyses.id */
    PARSE_RESUME,
    /** entity id = applications.id (handler provided by WP-BE-B) */
    MATCH_APPLICATION
}
