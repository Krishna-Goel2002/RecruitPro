package com.recruitpro.retention;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retention periods and batch limits ({@code app.retention.*}). Every period is in days and must be at least 1,
 * except {@code audit-days}, where 0 keeps audit events forever. Invalid values stop the application at start-up
 * instead of purging more than intended.
 */
@Component
public class RetentionSettings {

    private final int batchSize;
    private final int maxBatchesPerTable;
    private final int tokenDays;
    private final int jobDoneDays;
    private final int jobFailedDays;
    private final int notificationDays;
    private final int stateDays;
    private final int rateLimitDays;
    private final int referenceDays;
    private final int auditDays;
    private final int assistantConversationDays;

    public RetentionSettings(int batchSize, int maxBatchesPerTable, int tokenDays, int jobDoneDays, int jobFailedDays,
                             int notificationDays, int stateDays, int rateLimitDays, int referenceDays, int auditDays) {
        this(batchSize, maxBatchesPerTable, tokenDays, jobDoneDays, jobFailedDays, notificationDays, stateDays,
                rateLimitDays, referenceDays, auditDays, 180);
    }

    @Autowired
    public RetentionSettings(@Value("${app.retention.batch-size:500}") int batchSize,
                             @Value("${app.retention.max-batches-per-table:200}") int maxBatchesPerTable,
                             @Value("${app.retention.token-days:30}") int tokenDays,
                             @Value("${app.retention.job-done-days:30}") int jobDoneDays,
                             @Value("${app.retention.job-failed-days:90}") int jobFailedDays,
                             @Value("${app.retention.notification-days:180}") int notificationDays,
                             @Value("${app.retention.state-days:1}") int stateDays,
                             @Value("${app.retention.rate-limit-days:1}") int rateLimitDays,
                             @Value("${app.retention.reference-days:365}") int referenceDays,
                             @Value("${app.retention.audit-days:730}") int auditDays,
                             @Value("${app.retention.assistant-conversation-days:180}") int assistantConversationDays) {
        this.batchSize = require("batch-size", batchSize, 1, 10_000);
        this.maxBatchesPerTable = require("max-batches-per-table", maxBatchesPerTable, 1, 100_000);
        this.tokenDays = require("token-days", tokenDays, 1, 36_500);
        this.jobDoneDays = require("job-done-days", jobDoneDays, 1, 36_500);
        this.jobFailedDays = require("job-failed-days", jobFailedDays, 1, 36_500);
        this.notificationDays = require("notification-days", notificationDays, 1, 36_500);
        this.stateDays = require("state-days", stateDays, 1, 36_500);
        this.rateLimitDays = require("rate-limit-days", rateLimitDays, 1, 36_500);
        this.referenceDays = require("reference-days", referenceDays, 1, 36_500);
        this.auditDays = require("audit-days", auditDays, 0, 36_500);
        this.assistantConversationDays = require("assistant-conversation-days", assistantConversationDays, 1, 36_500);
    }

    private static int require(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalStateException("app.retention." + name + " must be between " + min + " and " + max
                    + " (was " + value + ")");
        }
        return value;
    }

    public int batchSize() {
        return batchSize;
    }

    public int maxBatchesPerTable() {
        return maxBatchesPerTable;
    }

    public int tokenDays() {
        return tokenDays;
    }

    public int jobDoneDays() {
        return jobDoneDays;
    }

    public int jobFailedDays() {
        return jobFailedDays;
    }

    public int notificationDays() {
        return notificationDays;
    }

    public int stateDays() {
        return stateDays;
    }

    public int rateLimitDays() {
        return rateLimitDays;
    }

    public int referenceDays() {
        return referenceDays;
    }

    /** 0 = audit events are kept forever. */
    public int auditDays() {
        return auditDays;
    }

    /** HR assistant conversations (with their messages and actions) without activity for this many days (AS-BE). */
    public int assistantConversationDays() {
        return assistantConversationDays;
    }
}
