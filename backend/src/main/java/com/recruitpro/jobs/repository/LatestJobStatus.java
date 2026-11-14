package com.recruitpro.jobs.repository;

import com.recruitpro.jobs.entity.BackgroundJobStatus;

/** Status of the most recent background job of one type for an entity. */
public record LatestJobStatus(Long entityId, BackgroundJobStatus status) {
}
