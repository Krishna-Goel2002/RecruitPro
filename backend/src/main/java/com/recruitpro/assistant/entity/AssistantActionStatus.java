package com.recruitpro.assistant.entity;

/**
 * Proposed action lifecycle: PENDING (waiting for the user, at most 30 minutes) &rarr; CONFIRMED (executed),
 * CANCELLED (by the user), EXPIRED (not confirmed in time) or FAILED (confirmed, but the re-validation or the
 * execution refused it; nothing changed).
 */
public enum AssistantActionStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    FAILED
}
