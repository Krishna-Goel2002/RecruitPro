package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** A verification run (POST /sources/{id}/runs, RECHECK). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RunResponse(Long id, Long sourceId, String status, String requestedBy, String verifier,
                          LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode) {
}
