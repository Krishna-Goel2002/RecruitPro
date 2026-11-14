package com.recruitpro.hr.dto;

/** POST /api/v1/hr/jobs/{jobId}/rematch: number of applications now waiting for a match. */
public record RematchResponse(int enqueued) {
}
