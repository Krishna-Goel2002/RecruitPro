package com.recruitpro.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code app.assistant.*} (ASSISTANT_PLAN 1 "Cost and abuse"; AS-BE). Invalid values stop the application at start-up.
 * The read time-out of one planner call ({@code step-timeout-ms}) belongs to {@code AiServiceClient}.
 */
@Component
public class AssistantSettings {

    private final int maxSteps;
    private final long messageBudgetMs;
    private final int maxMessagesPerHour;
    private final int actionTtlMinutes;
    private final int historyMessages;

    public AssistantSettings(@Value("${app.assistant.max-steps:6}") int maxSteps,
                             @Value("${app.assistant.message-budget-ms:60000}") long messageBudgetMs,
                             @Value("${app.assistant.max-messages-per-hour:30}") int maxMessagesPerHour,
                             @Value("${app.assistant.action-ttl-minutes:30}") int actionTtlMinutes,
                             @Value("${app.assistant.history-messages:20}") int historyMessages) {
        this.maxSteps = (int) require("max-steps", maxSteps, 1, 20);
        this.messageBudgetMs = require("message-budget-ms", messageBudgetMs, 1_000, 600_000);
        this.maxMessagesPerHour = (int) require("max-messages-per-hour", maxMessagesPerHour, 0, 10_000);
        this.actionTtlMinutes = (int) require("action-ttl-minutes", actionTtlMinutes, 1, 1_440);
        this.historyMessages = (int) require("history-messages", historyMessages, 1, 100);
    }

    private static long require(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalStateException("app.assistant." + name + " must be between " + min + " and " + max
                    + " (was " + value + ")");
        }
        return value;
    }

    /** Planner calls per user message (6). */
    public int maxSteps() {
        return maxSteps;
    }

    /** Wall-clock budget of one user message, all planner calls and tools included (60 s). */
    public long messageBudgetMs() {
        return messageBudgetMs;
    }

    /** User messages per user per hour (30); 0 switches the limit off. */
    public int maxMessagesPerHour() {
        return maxMessagesPerHour;
    }

    /** Minutes until a proposed action expires (30). */
    public int actionTtlMinutes() {
        return actionTtlMinutes;
    }

    /** Messages of the conversation sent to the planner (the last 20). */
    public int historyMessages() {
        return historyMessages;
    }
}
