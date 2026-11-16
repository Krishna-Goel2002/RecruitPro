package com.recruitpro.retention;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.time.UtcTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Data-retention purge (WP-OPS). Deletes rows that have served their purpose, in batches of
 * {@code app.retention.batch-size}, each batch in its own short transaction (select the oldest matching keys, then
 * delete or anonymise exactly those keys with the condition repeated, so a row that changed in between is left alone):
 *
 * <ul>
 *   <li>{@code user_tokens}: used or expired more than {@code token-days} (30) ago;</li>
 *   <li>{@code refresh_tokens}: revoked or expired more than {@code token-days} ago (a token is refused as expired
 *       before reuse detection looks at it, so deleting it changes no sign-in outcome);</li>
 *   <li>{@code background_jobs}: DONE for more than {@code job-done-days} (30), FAILED/DEAD for more than
 *       {@code job-failed-days} (90); PENDING and RUNNING jobs are never touched;</li>
 *   <li>{@code notifications}: e-mails SENT or SKIPPED, and read in-app messages, older than
 *       {@code notification-days} (180); unread in-app messages, PENDING and FAILED e-mails are kept;</li>
 *   <li>{@code sso_login_states}, {@code verification_oauth_states}: used or expired more than {@code state-days} (1)
 *       ago;</li>
 *   <li>{@code rate_limit_buckets}: windows that started more than {@code rate-limit-days} (1) ago;</li>
 *   <li>{@code verification_reference_requests} that ended EXPIRED, CANCELLED or DECLINED more than
 *       {@code reference-days} (365) ago: the row stays (verdict history, counts), the referee's name and e-mail are
 *       replaced by placeholders and the answers and comment are cleared;</li>
 *   <li>{@code assistant_conversations} (with their messages and proposed actions) whose last activity is more than
 *       {@code assistant-conversation-days} (180) ago (AS-BE);</li>
 *   <li>{@code audit_events} older than {@code audit-days} (730; 0 keeps them forever).</li>
 * </ul>
 *
 * The counts are logged and written as one {@code RETENTION_PURGE} audit event. Several backend instances may run the
 * purge at the same time: every statement is idempotent and keys are taken in ascending order.
 */
@Service
public class RetentionPurgeService {

    public static final String AUDIT_ACTION = "RETENTION_PURGE";
    static final String REMOVED_NAME = "[removed]";
    static final String REMOVED_EMAIL = "removed@retention.invalid";

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final AuditService auditService;
    private final RetentionSettings settings;
    private final AtomicBoolean running = new AtomicBoolean();

    public RetentionPurgeService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                                 AuditService auditService, RetentionSettings settings) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditService = auditService;
        this.settings = settings;
    }

    /** Runs every rule now; returns the number of rows deleted or anonymised per rule. */
    public RetentionReport purge() {
        return purge(UtcTime.now());
    }

    /** Runs every rule relative to {@code now} (UTC). Returns an empty report when a run is already in progress. */
    public RetentionReport purge(LocalDateTime now) {
        if (!running.compareAndSet(false, true)) {
            log.info("Retention purge skipped: a run is already in progress");
            return new RetentionReport(Map.of(), List.of());
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> failed = new ArrayList<>();
            for (Rule rule : rules(now)) {
                try {
                    counts.put(rule.name(), run(rule));
                } catch (RuntimeException ex) {
                    failed.add(rule.name());
                    log.warn("Retention purge of {} failed: {}", rule.name(), ex.getClass().getSimpleName());
                }
            }
            RetentionReport report = new RetentionReport(Collections.unmodifiableMap(counts), List.copyOf(failed));
            String details = describe(report);
            log.info("Retention purge finished: {}", details);
            try {
                auditService.recordFor(null, null, "SYSTEM", AUDIT_ACTION, "RETENTION", null, details);
            } catch (RuntimeException ex) {
                log.warn("Retention purge audit event could not be written: {}", ex.getClass().getSimpleName());
            }
            return report;
        } finally {
            running.set(false);
        }
    }

    private List<Rule> rules(LocalDateTime now) {
        Timestamp tokens = cutoff(now, settings.tokenDays());
        Timestamp states = cutoff(now, settings.stateDays());
        Timestamp notifications = cutoff(now, settings.notificationDays());
        List<Rule> rules = new ArrayList<>();
        rules.add(Rule.delete("user_tokens", "user_tokens", "id",
                "(used_at < ? OR expires_at < ?)", tokens, tokens));
        rules.add(Rule.delete("refresh_tokens", "refresh_tokens", "id",
                "(revoked_at < ? OR expires_at < ?)", tokens, tokens));
        rules.add(Rule.delete("background_jobs_done", "background_jobs", "id",
                "status = 'DONE' AND updated_at < ?", cutoff(now, settings.jobDoneDays())));
        rules.add(Rule.delete("background_jobs_failed", "background_jobs", "id",
                "status IN ('FAILED', 'DEAD') AND updated_at < ?", cutoff(now, settings.jobFailedDays())));
        rules.add(Rule.delete("notifications", "notifications", "id",
                "created_at < ? AND ((channel = 'EMAIL' AND status IN ('SENT', 'SKIPPED'))"
                        + " OR (channel = 'IN_APP' AND read_at IS NOT NULL))", notifications));
        rules.add(Rule.delete("sso_login_states", "sso_login_states", "id",
                "(used_at < ? OR expires_at < ?)", states, states));
        rules.add(Rule.delete("verification_oauth_states", "verification_oauth_states", "id",
                "(used_at < ? OR expires_at < ?)", states, states));
        rules.add(Rule.delete("rate_limit_buckets", "rate_limit_buckets", "bucket_key",
                "window_start < ?", cutoff(now, settings.rateLimitDays())));
        rules.add(Rule.update("reference_requests_anonymised", "verification_reference_requests", "id",
                "referee_name = ?, referee_email = ?, answers_json = NULL, referee_comment = NULL, updated_at = ?",
                List.<Object>of(REMOVED_NAME, REMOVED_EMAIL, Timestamp.valueOf(now)),
                "status IN ('EXPIRED', 'CANCELLED', 'DECLINED') AND updated_at < ? AND referee_email <> ?",
                cutoff(now, settings.referenceDays()), REMOVED_EMAIL));
        // AS-BE: HR assistant conversations without activity for assistant-conversation-days (180); their messages and
        // proposed actions go with them (ON DELETE CASCADE).
        rules.add(Rule.delete("assistant_conversations", "assistant_conversations", "id",
                "updated_at < ?", cutoff(now, settings.assistantConversationDays())));
        if (settings.auditDays() > 0) {
            rules.add(Rule.delete("audit_events", "audit_events", "id",
                    "created_at < ?", cutoff(now, settings.auditDays())));
        }
        return rules;
    }

    /** Runs one rule batch by batch until a batch is not full or the per-table batch limit is reached. */
    private int run(Rule rule) {
        int total = 0;
        for (int batch = 0; batch < settings.maxBatchesPerTable(); batch++) {
            Integer changed = transactions.execute(status -> runBatch(rule));
            int count = changed == null ? 0 : changed;
            total += count;
            if (count < settings.batchSize()) {
                return total;
            }
        }
        log.info("Retention purge of {} stopped after {} batches; the rest follows on the next run", rule.name(),
                settings.maxBatchesPerTable());
        return total;
    }

    private int runBatch(Rule rule) {
        List<Object> selectArgs = new ArrayList<>(rule.conditionArgs());
        selectArgs.add(settings.batchSize());
        String select = "SELECT " + rule.key() + " FROM " + rule.table() + " WHERE " + rule.condition()
                + " ORDER BY " + rule.key() + " LIMIT ?";
        List<?> keys = jdbcTemplate.queryForList(select, rule.keyType(), selectArgs.toArray());
        if (keys.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", Collections.nCopies(keys.size(), "?"));
        String where = " WHERE (" + rule.condition() + ") AND " + rule.key() + " IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>(rule.setArgs());
        args.addAll(rule.conditionArgs());
        args.addAll(keys);
        String statement = rule.setClause() == null
                ? "DELETE FROM " + rule.table() + where
                : "UPDATE " + rule.table() + " SET " + rule.setClause() + where;
        return jdbcTemplate.update(statement, args.toArray());
    }

    private static Timestamp cutoff(LocalDateTime now, int days) {
        return Timestamp.valueOf(now.minusDays(days));
    }

    static String describe(RetentionReport report) {
        String counts = report.counts().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        StringBuilder details = new StringBuilder(counts.isEmpty() ? "nothing run" : counts);
        if (!report.counts().containsKey("audit_events")) {
            details.append(", audit_events=kept");
        }
        if (!report.failed().isEmpty()) {
            details.append("; failed: ").append(String.join(", ", report.failed()));
        }
        return details.toString();
    }

    /**
     * One retention rule. {@code condition} is a constant SQL predicate with {@code ?} placeholders bound to
     * {@code conditionArgs}; {@code setClause} is null for deletes.
     */
    private record Rule(String name, String table, String key, Class<?> keyType, String setClause,
                        List<Object> setArgs, String condition, List<Object> conditionArgs) {

        static Rule delete(String name, String table, String key, String condition, Object... conditionArgs) {
            return new Rule(name, table, key, "id".equals(key) ? Long.class : String.class, null, List.of(), condition,
                    List.of(conditionArgs));
        }

        static Rule update(String name, String table, String key, String setClause, List<Object> setArgs,
                           String condition, Object... conditionArgs) {
            return new Rule(name, table, key, "id".equals(key) ? Long.class : String.class, setClause, setArgs,
                    condition, List.of(conditionArgs));
        }
    }
}
