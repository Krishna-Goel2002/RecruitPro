package com.recruitpro.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Times outgoing calls into a Micrometer timer (e.g. {@code recruitpro.ai.call}) tagged with {@code endpoint} (the
 * request path, which has no ids for the AI service) and {@code outcome} (SUCCESS, CLIENT_ERROR, SERVER_ERROR,
 * IO_ERROR). Does nothing while no registry is available.
 */
public class TimedClientInterceptor implements ClientHttpRequestInterceptor {

    private final String timerName;
    private final Supplier<MeterRegistry> registry;

    public TimedClientInterceptor(String timerName, Supplier<MeterRegistry> registry) {
        this.timerName = timerName;
        this.registry = registry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        MeterRegistry meters = registry.get();
        if (meters == null) {
            return execution.execute(request, body);
        }
        long start = System.nanoTime();
        String outcome = "IO_ERROR";
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            outcome = status >= 500 ? "SERVER_ERROR" : status >= 400 ? "CLIENT_ERROR" : "SUCCESS";
            return response;
        } finally {
            String path = request.getURI() == null ? "" : request.getURI().getPath();
            Timer.builder(timerName)
                    .tag("endpoint", path == null || path.isEmpty() ? "/" : path)
                    .tag("outcome", outcome)
                    .register(meters)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
