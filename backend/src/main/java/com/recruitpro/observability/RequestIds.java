package com.recruitpro.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request correlation ids (plan v2, Section 6.4). The id of the current request or background job lives in the
 * SLF4J MDC under {@value #MDC_KEY} and travels as the {@value #HEADER} header to the ai-service and agent-service.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    /** MDC key; WP-VBE's AgentServiceClient reads the same key. */
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private RequestIds() {
    }

    /** Whether an incoming id may be reused (8-64 characters of letters, digits, dot, underscore, hyphen). */
    public static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The current request id, or null outside a request or job. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
