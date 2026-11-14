package com.recruitpro.admin.dto;

/** M11: real health of the backend's dependencies. Values: UP, DOWN, UNCONFIGURED. */
public record SystemHealthResponse(String backend, String database, String aiService, QueueHealth queue) {

    public record QueueHealth(long pending, long failed, long dead) {
    }
}
