package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.SystemHealthResponse;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * M11: database {@code SELECT 1}, AI service {@code GET /health} (2-second timeout, result cached for 15
 * seconds) and background-queue counts.
 */
@Service
public class SystemHealthService {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String UNCONFIGURED = "UNCONFIGURED";

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);
    private static final long AI_CACHE_MILLIS = 15_000L;

    private final JdbcTemplate jdbcTemplate;
    private final BackgroundJobRepository backgroundJobRepository;
    private final RestClient aiHealthClient;
    private final boolean aiConfigured;

    private volatile String cachedAiStatus;
    private volatile long cachedAiAt;

    public SystemHealthService(JdbcTemplate jdbcTemplate, BackgroundJobRepository backgroundJobRepository,
                               RestClient.Builder restClientBuilder,
                               @Value("${app.ai-service.base-url:}") String aiBaseUrl,
                               @Value("${app.ai-service.health-timeout-ms:2000}") int healthTimeoutMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.backgroundJobRepository = backgroundJobRepository;
        this.aiConfigured = aiBaseUrl != null && !aiBaseUrl.isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(healthTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(healthTimeoutMs));
        this.aiHealthClient = aiConfigured
                ? restClientBuilder.clone().baseUrl(aiBaseUrl).requestFactory(factory).build()
                : null;
    }

    public SystemHealthResponse check() {
        return new SystemHealthResponse(UP, databaseStatus(), aiServiceStatus(), queueHealth());
    }

    public String databaseStatus() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1 ? UP : DOWN;
        } catch (RuntimeException ex) {
            log.warn("Database health check failed: {}", ex.getClass().getSimpleName());
            return DOWN;
        }
    }

    public String aiServiceStatus() {
        if (!aiConfigured) {
            return UNCONFIGURED;
        }
        long now = System.currentTimeMillis();
        String cached = cachedAiStatus;
        if (cached != null && now - cachedAiAt < AI_CACHE_MILLIS) {
            return cached;
        }
        String status;
        try {
            aiHealthClient.get().uri("/health").retrieve().toBodilessEntity();
            status = UP;
        } catch (RuntimeException ex) {
            log.debug("AI service health check failed: {}", ex.getClass().getSimpleName());
            status = DOWN;
        }
        cachedAiStatus = status;
        cachedAiAt = now;
        return status;
    }

    public SystemHealthResponse.QueueHealth queueHealth() {
        try {
            return new SystemHealthResponse.QueueHealth(
                    backgroundJobRepository.countByStatus(BackgroundJobStatus.PENDING),
                    backgroundJobRepository.countByStatus(BackgroundJobStatus.FAILED),
                    backgroundJobRepository.countByStatus(BackgroundJobStatus.DEAD));
        } catch (RuntimeException ex) {
            log.warn("Queue health check failed: {}", ex.getClass().getSimpleName());
            return new SystemHealthResponse.QueueHealth(-1, -1, -1);
        }
    }
}
