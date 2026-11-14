package com.recruitpro.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Deletes all application rows in foreign-key order. Integration tests share one in-memory H2 database
 * (MODE=PostgreSQL) across Spring contexts, so every test class cleans before and after itself.
 * Packages that add tables must add them here (children before parents).
 */
public final class TestDatabase {

    private static final List<String> TABLES_CHILDREN_FIRST = List.of(
            "background_jobs",
            "notifications",
            "interview_scorecards",
            "interviews",
            "interview_kits",
            "work_sample_assignments",
            "work_samples",
            "eeo_responses",
            "audit_events",
            "user_tokens",
            "refresh_tokens",
            "invitations",
            "application_decision_history",
            "job_match_runs",
            "applications",
            "candidate_claims",
            "resume_analysis_facts",
            "resume_analyses",
            "candidate_resumes",
            "job_requirements",
            "jobs",
            "hr_profiles",
            "candidate_profiles",
            "users",
            "organizations");

    private TestDatabase() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        for (String table : TABLES_CHILDREN_FIRST) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
