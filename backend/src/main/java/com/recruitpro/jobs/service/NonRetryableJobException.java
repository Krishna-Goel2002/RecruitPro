package com.recruitpro.jobs.service;

/** Thrown by a {@link BackgroundJobHandler} when retrying cannot succeed; the job becomes FAILED. */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
