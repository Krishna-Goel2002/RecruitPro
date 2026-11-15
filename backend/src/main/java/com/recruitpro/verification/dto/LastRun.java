package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** The latest run of a source, inside SourceResponse. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LastRun(Long id, String status, LocalDateTime finishedAt, String errorCode) {
}
