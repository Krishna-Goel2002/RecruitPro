package com.recruitpro.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a caller exceeded a rate limit. Results in HTTP 429 Too Many Requests with a {@code Retry-After}
 * header (seconds; integration pass: every 429 carries one). Business limits that waiting does not lift (open
 * reference requests, connected sources) are 409 Conflict instead, not this exception.
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class TooManyRequestsException extends RuntimeException {

    /** Used when the limit does not know when it frees up. */
    public static final long DEFAULT_RETRY_AFTER_SECONDS = 60;

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(message, DEFAULT_RETRY_AFTER_SECONDS);
    }

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Whole seconds until a retry can succeed (at least 1). */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
