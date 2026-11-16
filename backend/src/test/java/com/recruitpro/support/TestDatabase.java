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
            // AS-BE HR assistant tables (children first).
            "assistant_actions",
            "assistant_messages",
            "assistant_conversations",
            // WP-VBE verification tables (children first).
            "claim_verdict_labels",
            "claim_verdict_reviews",
            "claim_verdicts",
            "evidence_items",
            "verification_runs",
            "verification_sources",
            "verification_reference_requests",
            "verification_oauth_states",
            "organization_verification_settings",
            // WP-PLAT tables (children first).
            "user_recovery_codes",
            "user_mfa",
            "user_identities",
            "sso_login_states",
            "rate_limit_buckets",
            "work_sample_submission_files",
            "interview_scorecard_revisions",
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
            "candidate_claim_verifications",
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
