package com.recruitpro.verification.client;

/**
 * A non-2xx answer of the agent service. {@code code} is {@code detail.code} of its error body
 * ({@code {"detail": {"code", "message"}}}) or null when the body has none (authentication, validation, proxies).
 */
public class AgentServiceException extends RuntimeException {

    private final int status;
    private final String code;

    public AgentServiceException(int status, String code, String message) {
        super(message == null ? "Agent service responded with HTTP " + status : message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** True for answers worth retrying later: no error code and HTTP 5xx, 408 or 429. */
    public boolean isTransient() {
        return code == null && (status >= 500 || status == 408 || status == 429);
    }
}
