package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** A submitted work-sample file: {@code {id, originalName, contentType, sizeBytes, sha256, uploadedAt}}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkSampleFileResponse(Long id, String originalName, String contentType, long sizeBytes, String sha256,
                                     LocalDateTime uploadedAt) {
}
