package com.recruitpro.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Client address used for rate limiting and audit records. Relies on the servlet remote address; when the
 * backend runs behind a trusted proxy, enable {@code server.forward-headers-strategy} so it is the real client.
 */
public final class ClientIp {

    private static final int MAX_LENGTH = 64;

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        return address.length() > MAX_LENGTH ? address.substring(0, MAX_LENGTH) : address;
    }

    /** The client address of the current HTTP request, if this thread is serving one. */
    public static Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return Optional.of(of(servletAttributes.getRequest()));
        }
        return Optional.empty();
    }
}
