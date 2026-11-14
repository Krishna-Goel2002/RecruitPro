package com.recruitpro.jobs.entity;

/**
 * PENDING (waiting for run_after), RUNNING (claimed by a worker), DONE, FAILED (permanent failure, not
 * retried), DEAD (retries exhausted).
 */
public enum BackgroundJobStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DEAD
}
