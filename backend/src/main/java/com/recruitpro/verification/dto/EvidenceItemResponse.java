package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/** A stored evidence item (the snapshot text itself is not returned; its hash is). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EvidenceItemResponse(Long id, String key, String kind, String url, String title, LocalDateTime retrievedAt,
                                   String contentHash, String excerpt, JsonNode attributes) {
}
