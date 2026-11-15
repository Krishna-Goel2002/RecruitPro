package com.recruitpro.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled data-retention purge ({@code app.retention.enabled}, default true; off in the test profile). Runs on the
 * cron expression {@code app.retention.cron} (default {@code 0 30 3 * * *}, 03:30 UTC daily; {@code -} disables the
 * schedule). The work itself is in {@link RetentionPurgeService}.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final RetentionPurgeService purgeService;
    private final boolean enabled;

    public RetentionPurgeJob(RetentionPurgeService purgeService,
                             @Value("${app.retention.enabled:true}") boolean enabled) {
        this.purgeService = purgeService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}", zone = "UTC")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            purgeService.purge();
        } catch (RuntimeException ex) {
            log.warn("Retention purge failed: {}", ex.getClass().getSimpleName());
        }
    }

    boolean isEnabled() {
        return enabled;
    }
}
