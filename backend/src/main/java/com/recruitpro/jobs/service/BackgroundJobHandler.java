package com.recruitpro.jobs.service;

import com.recruitpro.jobs.entity.BackgroundJobType;

/**
 * Executes one {@link BackgroundJobType}. Implementations are Spring beans; exactly one handler per type.
 *
 * <p>{@link #handle(long)} runs outside any worker transaction (handlers manage their own). Throwing marks
 * the attempt failed and schedules a retry with exponential backoff; throwing
 * {@link NonRetryableJobException} fails the job permanently. Permanent business outcomes should be recorded
 * by the handler and then return normally.</p>
 */
public interface BackgroundJobHandler {

    BackgroundJobType type();

    void handle(long entityId) throws Exception;

    /** Called once when retries are exhausted (job DEAD) so the handler can mark its entity as failed. */
    default void onDeadLetter(long entityId, String lastError) {
    }
}
