package com.recruitpro.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter of every request: reuses a safe incoming {@code X-Request-Id} (8-64 characters of
 * {@code [A-Za-z0-9._-]}) or generates a UUID, puts it into the MDC ({@code requestId}) for the duration of the
 * request and echoes it in the response header. Registered at the highest precedence by {@link ObservabilityConfig}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** Request attribute holding the id (for code that has the request but runs outside the MDC thread). */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // An error or async dispatch of the same request keeps the id chosen on the first pass.
        Object prior = request.getAttribute(ATTRIBUTE);
        String incoming = request.getHeader(RequestIds.HEADER);
        String requestId = prior instanceof String earlier ? earlier
                : RequestIds.isSafe(incoming) ? incoming : RequestIds.generate();
        String previous = MDC.get(RequestIds.MDC_KEY);
        MDC.put(RequestIds.MDC_KEY, requestId);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(RequestIds.MDC_KEY);
            } else {
                MDC.put(RequestIds.MDC_KEY, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
