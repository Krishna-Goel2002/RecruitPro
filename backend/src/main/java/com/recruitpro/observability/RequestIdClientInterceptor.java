package com.recruitpro.observability;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Adds the current request id (MDC {@code requestId}) as {@code X-Request-Id} to outgoing RestClient calls. */
public class RequestIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = RequestIds.current();
        if (requestId != null && !request.getHeaders().containsKey(RequestIds.HEADER)) {
            request.getHeaders().set(RequestIds.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
