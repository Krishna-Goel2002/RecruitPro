package com.recruitpro.retention;

import com.recruitpro.audit.entity.AuditEvent;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** WP-OPS data-retention purge: what is deleted, what is kept, batching, audit event and settings validation. */
@SpringBootTest
@ActiveProfiles("test")
class RetentionPurgeIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditService auditService;
    @Autowired AuditEventRepository auditEvents;
    @Autowired RetentionPurgeService purgeService;
    @Autowired RetentionPurgeJob purgeJob;
    @Autowired TestDataFactory fixtures;

    private LocalDateTime now;
    private Long userId;
    private Long candidateId;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        now = UtcTime.now();
        CandidateProfile candidate = fixtures.candidate("retention@example.test");
        candidateId = candidate.getId();
        userId = candidate.getUser().getId();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void purgeDeletesOnlyRowsThatHaveServedTheirPurpose() {
        // user_tokens: used 40 days ago and expired 40 days ago go; used 5 days ago and still valid stay.
        userToken("ut-used-old", daysAgo(40), daysAgo(41));
        userToken("ut-expired-old", null, daysAgo(40));
        userToken("ut-used-recent", daysAgo(5), daysAgo(4));
        userToken("ut-valid", null, now.plusHours(1));
        // refresh_tokens: revoked 31 days ago and expired 35 days ago go; revoked yesterday and active stay.
        refreshToken("rt-revoked-old", daysAgo(31), daysAgo(20));
        refreshToken("rt-expired-old", null, daysAgo(35));
        refreshToken("rt-revoked-recent", daysAgo(1), now.plusDays(10));
        refreshToken("rt-active", null, now.plusDays(14));
        // background_jobs: DONE 31 days, DEAD 91 days go; DONE 10 days, FAILED 60 days, old PENDING/RUNNING stay.
        long doneOld = job("DONE", daysAgo(31));
        long deadOld = job("DEAD", daysAgo(91));
        long doneRecent = job("DONE", daysAgo(10));
        long failedRecent = job("FAILED", daysAgo(60));
        long pendingOld = job("PENDING", daysAgo(400));
        long runningOld = job("RUNNING", daysAgo(400));
        // notifications: old SENT and SKIPPED e-mails and an old read inbox message go; the rest stays.
        long sentOld = notification("EMAIL", "SENT", null, daysAgo(181));
        long skippedOld = notification("EMAIL", "SKIPPED", null, daysAgo(200));
        long readOld = notification("IN_APP", "SENT", daysAgo(150), daysAgo(181));
        long unreadOld = notification("IN_APP", "SENT", null, daysAgo(400));
        long failedEmailOld = notification("EMAIL", "FAILED", null, daysAgo(400));
        long pendingEmailOld = notification("EMAIL", "PENDING", null, daysAgo(400));
        long sentRecent = notification("EMAIL", "SENT", null, daysAgo(179));
        // Login states: used or expired more than a day ago go.
        ssoState("sso-used-old", daysAgo(2), now.plusMinutes(5));
        ssoState("sso-expired-old", null, daysAgo(2));
        ssoState("sso-open", null, now.plusMinutes(10));
        oauthState("oa-expired-old", null, daysAgo(3));
        oauthState("oa-used-recent", now.minusHours(2), now.plusMinutes(5));
        // rate_limit_buckets: windows older than a day go.
        bucket("rl-old", daysAgo(2));
        bucket("rl-recent", now.minusMinutes(30));
        // Reference requests: ended (EXPIRED/CANCELLED/DECLINED) more than 365 days ago are anonymised, never deleted.
        long expiredOld = reference("EXPIRED", daysAgo(400), "{\"q1\":\"yes\"}", "Great colleague");
        long declinedOld = reference("DECLINED", daysAgo(366), null, "Prefer not to answer");
        long answeredOld = reference("ANSWERED", daysAgo(400), "{\"q1\":\"yes\"}", "Kept for the verdict");
        long cancelledRecent = reference("CANCELLED", daysAgo(100), null, null);
        // audit_events: older than 730 days go.
        long auditOld = audit("OLD_ACTION", daysAgo(731));
        long auditRecent = audit("RECENT_ACTION", daysAgo(700));

        RetentionReport report = purgeService.purge();

        assertEquals(List.of(), report.failed());
        assertEquals(2, report.count("user_tokens"));
        assertEquals(2, report.count("refresh_tokens"));
        assertEquals(1, report.count("background_jobs_done"));
        assertEquals(1, report.count("background_jobs_failed"));
        assertEquals(3, report.count("notifications"));
        assertEquals(2, report.count("sso_login_states"));
        assertEquals(1, report.count("verification_oauth_states"));
        assertEquals(1, report.count("rate_limit_buckets"));
        assertEquals(2, report.count("reference_requests_anonymised"));
        assertEquals(1, report.count("audit_events"));

        assertEquals(List.of("ut-used-recent", "ut-valid"), strings("SELECT token_hash FROM user_tokens ORDER BY token_hash"));
        assertEquals(List.of("rt-active", "rt-revoked-recent"), strings("SELECT token_hash FROM refresh_tokens ORDER BY token_hash"));
        assertEquals(List.of(doneRecent, failedRecent, pendingOld, runningOld).stream().sorted().toList(),
                ids("SELECT id FROM background_jobs ORDER BY id"));
        assertFalse(ids("SELECT id FROM background_jobs").contains(doneOld));
        assertFalse(ids("SELECT id FROM background_jobs").contains(deadOld));
        assertEquals(List.of(unreadOld, failedEmailOld, pendingEmailOld, sentRecent).stream().sorted().toList(),
                ids("SELECT id FROM notifications ORDER BY id"));
        assertFalse(ids("SELECT id FROM notifications").contains(sentOld));
        assertFalse(ids("SELECT id FROM notifications").contains(skippedOld));
        assertFalse(ids("SELECT id FROM notifications").contains(readOld));
        assertEquals(List.of("sso-open"), strings("SELECT state_hash FROM sso_login_states"));
        assertEquals(List.of("oa-used-recent"), strings("SELECT state_hash FROM verification_oauth_states"));
        assertEquals(List.of("rl-recent"), strings("SELECT bucket_key FROM rate_limit_buckets"));

        assertEquals(4, count("SELECT COUNT(*) FROM verification_reference_requests"));
        for (long id : List.of(expiredOld, declinedOld)) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT referee_name, referee_email, answers_json, referee_comment FROM verification_reference_requests WHERE id = ?", id);
            assertEquals(RetentionPurgeService.REMOVED_NAME, row.get("referee_name"));
            assertEquals(RetentionPurgeService.REMOVED_EMAIL, row.get("referee_email"));
            assertNull(row.get("answers_json"));
            assertNull(row.get("referee_comment"));
        }
        for (long id : List.of(answeredOld, cancelledRecent)) {
            assertEquals("referee" + id + "@example.test", jdbcTemplate.queryForObject(
                    "SELECT referee_email FROM verification_reference_requests WHERE id = ?", String.class, id));
        }

        List<Long> auditIds = ids("SELECT id FROM audit_events WHERE action <> 'RETENTION_PURGE'");
        assertEquals(List.of(auditRecent), auditIds);
        assertFalse(auditIds.contains(auditOld));
        List<AuditEvent> purges = auditEvents.findAllByActionOrderByIdAsc(RetentionPurgeService.AUDIT_ACTION);
        assertEquals(1, purges.size());
        assertEquals("SYSTEM", purges.get(0).getActorEmail());
        assertNull(purges.get(0).getOrganizationId());
        assertTrue(purges.get(0).getDetails().contains("user_tokens=2"), purges.get(0).getDetails());
        assertTrue(purges.get(0).getDetails().contains("reference_requests_anonymised=2"), purges.get(0).getDetails());

        // A second run finds nothing more (anonymised references are not counted again).
        RetentionReport again = purgeService.purge();
        assertEquals(0, again.total());
        assertEquals(2, auditEvents.findAllByActionOrderByIdAsc(RetentionPurgeService.AUDIT_ACTION).size());
    }

    @Test
    void largeBacklogsAreDeletedInBatchesAndCappedPerRun() {
        for (int i = 0; i < 7; i++) {
            bucket("rl-" + i, daysAgo(3));
        }
        RetentionPurgeService capped = service(settings(2, 2, 730));
        assertEquals(4, capped.purge().count("rate_limit_buckets"));
        assertEquals(3, count("SELECT COUNT(*) FROM rate_limit_buckets"));

        RetentionPurgeService batched = service(settings(2, 100, 730));
        assertEquals(3, batched.purge().count("rate_limit_buckets"));
        assertEquals(0, count("SELECT COUNT(*) FROM rate_limit_buckets"));
    }

    @Test
    void auditDaysZeroKeepsAuditEventsForever() {
        long ancient = audit("ANCIENT_ACTION", daysAgo(5000));
        RetentionReport report = service(settings(500, 200, 0)).purge();
        assertFalse(report.counts().containsKey("audit_events"));
        assertTrue(ids("SELECT id FROM audit_events").contains(ancient));
        AuditEvent purge = auditEvents.findAllByActionOrderByIdAsc(RetentionPurgeService.AUDIT_ACTION).get(0);
        assertTrue(purge.getDetails().contains("audit_events=kept"), purge.getDetails());
    }

    @Test
    void invalidSettingsAreRejectedAtStartUp() {
        assertThrows(IllegalStateException.class, () -> settings(0, 200, 730));
        assertThrows(IllegalStateException.class, () -> settings(500, 200, -1));
        assertThrows(IllegalStateException.class,
                () -> new RetentionSettings(500, 200, 0, 30, 90, 180, 1, 1, 365, 730));
    }

    @Test
    void theScheduledJobIsOffInTheTestProfile() {
        bucket("rl-old", daysAgo(5));
        assertFalse(purgeJob.isEnabled());
        purgeJob.run();
        assertEquals(1, count("SELECT COUNT(*) FROM rate_limit_buckets"));
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(RetentionPurgeService.AUDIT_ACTION).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private RetentionSettings settings(int batchSize, int maxBatches, int auditDays) {
        return new RetentionSettings(batchSize, maxBatches, 30, 30, 90, 180, 1, 1, 365, auditDays);
    }

    private RetentionPurgeService service(RetentionSettings settings) {
        return new RetentionPurgeService(jdbcTemplate, transactionManager, auditService, settings);
    }

    private LocalDateTime daysAgo(int days) {
        return now.minusDays(days);
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private void userToken(String hash, LocalDateTime usedAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO user_tokens (user_id, purpose, token_hash, expires_at, used_at, created_at, updated_at) "
                + "VALUES (?, 'VERIFY_EMAIL', ?, ?, ?, ?, ?)", userId, hash, ts(expiresAt), ts(usedAt), ts(daysAgo(60)), ts(daysAgo(60)));
    }

    private void refreshToken(String hash, LocalDateTime revokedAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, revoked_at, family_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", userId, hash, ts(expiresAt), ts(revokedAt), "family-" + hash,
                ts(daysAgo(50)), ts(daysAgo(50)));
    }

    private long job(String status, LocalDateTime updatedAt) {
        jdbcTemplate.update("INSERT INTO background_jobs (job_type, entity_id, status, attempts, max_attempts, run_after, created_at, updated_at) "
                + "VALUES ('PARSE_RESUME', 1, ?, 1, 5, ?, ?, ?)", status, ts(updatedAt), ts(updatedAt), ts(updatedAt));
        return lastId("background_jobs");
    }

    private long notification(String channel, String status, LocalDateTime readAt, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO notifications (recipient_user_id, recipient_email, channel, template, subject, body, status, "
                + "attempts, read_at, created_at, updated_at) VALUES (?, 'retention@example.test', ?, 'APPLICATION_RECEIVED', "
                + "'Subject', 'Body', ?, 0, ?, ?, ?)", userId, channel, status, ts(readAt), ts(createdAt), ts(createdAt));
        return lastId("notifications");
    }

    private void ssoState(String hash, LocalDateTime usedAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO sso_login_states (state_hash, nonce_hash, code_verifier_enc, provider, portal, expires_at, "
                + "used_at, created_at, updated_at) VALUES (?, 'nonce', 'enc', 'google', 'staff', ?, ?, ?, ?)",
                hash, ts(expiresAt), ts(usedAt), ts(daysAgo(3)), ts(daysAgo(3)));
    }

    private void oauthState(String hash, LocalDateTime usedAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO verification_oauth_states (state_hash, candidate_id, provider, expires_at, used_at, created_at) "
                + "VALUES (?, ?, 'GITHUB', ?, ?, ?)", hash, candidateId, ts(expiresAt), ts(usedAt), ts(daysAgo(3)));
    }

    private void bucket(String key, LocalDateTime windowStart) {
        jdbcTemplate.update("INSERT INTO rate_limit_buckets (bucket_key, window_start, hit_count) VALUES (?, ?, 1)", key, ts(windowStart));
    }

    private long reference(String status, LocalDateTime updatedAt, String answers, String comment) {
        jdbcTemplate.update("INSERT INTO verification_reference_requests (candidate_id, referee_name, referee_email, relationship, "
                + "claim_refs_json, token_hash, status, answers_json, referee_comment, expires_at, consented_at, created_at, updated_at) "
                + "VALUES (?, 'Pat Referee', 'pending@example.test', 'PEER', '[]', ?, ?, ?, ?, ?, ?, ?, ?)",
                candidateId, "ref-" + status + "-" + updatedAt, status, answers, comment, ts(updatedAt), ts(updatedAt.minusDays(14)),
                ts(updatedAt.minusDays(14)), ts(updatedAt));
        long id = lastId("verification_reference_requests");
        jdbcTemplate.update("UPDATE verification_reference_requests SET referee_email = ? WHERE id = ?",
                "referee" + id + "@example.test", id);
        return id;
    }

    private long audit(String action, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO audit_events (action, actor_email, created_at, updated_at) VALUES (?, 'SYSTEM', ?, ?)",
                action, ts(createdAt), ts(createdAt));
        return lastId("audit_events");
    }

    private long lastId(String table) {
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
        assertNotNull(id);
        return id;
    }

    private List<Long> ids(String sql) {
        return jdbcTemplate.queryForList(sql, Long.class);
    }

    private List<String> strings(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
