package com.recruitpro.jobs.service;

import com.recruitpro.jobs.entity.BackgroundJob;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.observability.ObservabilityConfig;
import com.recruitpro.observability.RequestIds;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polls the queue every {@code app.background-jobs.poll-interval-ms} (default 2 s) when
 * {@code app.background-jobs.enabled} is true, claims up to {@code batch-size} due jobs and dispatches them
 * to the {@link BackgroundJobHandler} registered for their type. {@link #runOnce()} can be called directly
 * (tests, admin tooling) regardless of the enabled flag.
 */
@Component
public class BackgroundJobWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobWorker.class);

    private final BackgroundJobService jobService;
    private final Map<BackgroundJobType, BackgroundJobHandler> handlers;
    private final boolean enabled;
    private final int batchSize;
    /** Timer recruitpro.job.handle (tags type, outcome); null when Micrometer is absent (plain unit tests). */
    private volatile MeterRegistry meterRegistry;

    public BackgroundJobWorker(BackgroundJobService jobService,
                               List<BackgroundJobHandler> handlers,
                               @Value("${app.background-jobs.enabled:true}") boolean enabled,
                               @Value("${app.background-jobs.batch-size:5}") int batchSize) {
        this.jobService = jobService;
        Map<BackgroundJobType, BackgroundJobHandler> byType = new EnumMap<>(BackgroundJobType.class);
        for (BackgroundJobHandler handler : handlers) {
            BackgroundJobHandler previous = byType.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("More than one background job handler for " + handler.type());
            }
        }
        this.handlers = Collections.unmodifiableMap(byType);
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.background-jobs.poll-interval-ms:2000}",
            initialDelayString = "${app.background-jobs.initial-delay-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException ex) {
            log.warn("Background job polling failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Releases stale jobs, then claims and runs one batch synchronously. Returns the number of jobs run. */
    public int runOnce() {
        for (BackgroundJob dead : jobService.releaseStaleJobs()) {
            notifyDeadLetter(dead.getJobType(), dead.getEntityId(), dead.getLastError());
        }
        List<BackgroundJob> claimed = jobService.claimDue(batchSize);
        for (BackgroundJob job : claimed) {
            execute(job);
        }
        return claimed.size();
    }

    public boolean hasHandler(BackgroundJobType type) {
        return handlers.containsKey(type);
    }

    /**
     * Runs one job with its own request id in the MDC ({@code job-<id>-<random>}, sent on to the AI and agent
     * services) and records the {@code recruitpro.job.handle} timer (outcome success, retry, dead, failed).
     */
    private void execute(BackgroundJob job) {
        String previous = MDC.get(RequestIds.MDC_KEY);
        MDC.put(RequestIds.MDC_KEY, "job-" + job.getId() + "-" + UUID.randomUUID().toString().substring(0, 8));
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            outcome = run(job);
        } finally {
            record(job.getJobType(), outcome, System.nanoTime() - start);
            if (previous == null) {
                MDC.remove(RequestIds.MDC_KEY);
            } else {
                MDC.put(RequestIds.MDC_KEY, previous);
            }
        }
    }

    private String run(BackgroundJob job) {
        BackgroundJobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            log.warn("No handler registered for background job type {} (job {})", job.getJobType(), job.getId());
            jobService.markFailed(job.getId(), "No handler registered for " + job.getJobType());
            return "failed";
        }
        try {
            handler.handle(job.getEntityId());
            jobService.markDone(job.getId());
            return "success";
        } catch (NonRetryableJobException ex) {
            log.warn("Background job {} ({} #{}) failed permanently", job.getId(), job.getJobType(), job.getEntityId());
            jobService.markFailed(job.getId(), describe(ex));
            return "failed";
        } catch (Exception ex) {
            String error = describe(ex);
            BackgroundJobStatus status = jobService.markAttemptFailed(job.getId(), error);
            log.warn("Background job {} ({} #{}) attempt {} failed: {} -> {}", job.getId(), job.getJobType(),
                    job.getEntityId(), job.getAttempts(), error, status);
            if (status == BackgroundJobStatus.DEAD) {
                notifyDeadLetter(job.getJobType(), job.getEntityId(), error);
                return "dead";
            }
            return "retry";
        }
    }

    private void record(BackgroundJobType type, String outcome, long nanos) {
        MeterRegistry meters = meterRegistry;
        if (meters == null || type == null) {
            return;
        }
        Timer.builder(ObservabilityConfig.JOB_TIMER)
                .tag("type", type.name())
                .tag("outcome", outcome)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    private void notifyDeadLetter(BackgroundJobType type, Long entityId, String error) {
        BackgroundJobHandler handler = handlers.get(type);
        if (handler == null || entityId == null) {
            return;
        }
        try {
            handler.onDeadLetter(entityId, error);
        } catch (RuntimeException ex) {
            log.warn("Dead-letter callback for {} #{} failed: {}", type, entityId, ex.getClass().getSimpleName());
        }
    }

    /** Error text for last_error: exception type and message, without response bodies. */
    static String describe(Exception ex) {
        if (ex instanceof RestClientResponseException response) {
            return ex.getClass().getSimpleName() + ": HTTP " + response.getStatusCode().value();
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String text = ex.getClass().getSimpleName() + ": " + message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
