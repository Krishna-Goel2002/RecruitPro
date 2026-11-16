package com.recruitpro.assistant.service;

import com.recruitpro.ai.dto.AiAssistantStepRequest;
import com.recruitpro.ai.dto.AiAssistantStepResponse;
import com.recruitpro.ai.service.AiServiceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Planner port: one step of the ai-service planner ({@code AiServiceClient.assistantStep}) within the time left of the
 * user message. The HTTP call runs on a small bounded pool so the orchestrator can stop waiting when the message's
 * 60-second budget runs out (the call itself is also bounded by {@code app.assistant.step-timeout-ms}). No security
 * context or database transaction is involved in the call.
 */
@Component
public class AssistantPlanner {

    static final Set<String> STEP_TYPES = Set.of("tool_call", "ask", "propose", "answer");
    static final int MAX_CONCURRENT_CALLS = 16;

    private static final Logger log = LoggerFactory.getLogger(AssistantPlanner.class);

    private final AiServiceClient aiServiceClient;
    private final ThreadPoolExecutor executor;

    public AssistantPlanner(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(0, MAX_CONCURRENT_CALLS, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-planner-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** The next step; throws when the planner is unavailable or does not answer within {@code timeoutMs}. */
    public AiAssistantStepResponse step(AiAssistantStepRequest request, long timeoutMs) throws PlannerUnavailableException {
        if (timeoutMs <= 0) {
            throw new PlannerUnavailableException("The time limit of this message was reached", true);
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<AiAssistantStepResponse> future;
        try {
            future = executor.submit(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    return aiServiceClient.assistantStep(request);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException ex) {
            throw new PlannerUnavailableException("Too many assistant requests at the same time", false);
        }
        AiAssistantStepResponse response;
        try {
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new PlannerUnavailableException("The planner did not answer in time", true);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PlannerUnavailableException("Interrupted", false);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("Assistant planner call failed: {}", cause.getClass().getSimpleName());
            throw new PlannerUnavailableException("The planner is unavailable", false);
        }
        if (response == null || response.type() == null || !STEP_TYPES.contains(response.type())) {
            throw new PlannerUnavailableException("The planner returned no usable step", false);
        }
        return response;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
