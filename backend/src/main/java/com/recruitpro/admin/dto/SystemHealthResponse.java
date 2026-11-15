package com.recruitpro.admin.dto;

/**
 * M11: real health of the backend's dependencies. Values: UP, DOWN, UNCONFIGURED. {@code agentService} (plan v2,
 * WP-VBE) is the verification agent service, checked like the AI service.
 */
public record SystemHealthResponse(String backend, String database, String aiService, QueueHealth queue,
                                   String agentService) {

    public SystemHealthResponse(String backend, String database, String aiService, QueueHealth queue) {
        this(backend, database, aiService, queue, null);
    }

    public record QueueHealth(long pending, long failed, long dead) {
    }
}
