package com.recruitpro.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * A proposed action: {id, conversationId, action (tool name), summary, preview, status PENDING|CONFIRMED|CANCELLED|
 * EXPIRED|FAILED, expiresAt, result ({kind, id, link} or {kind: "failed", error}; null while pending), createdAt,
 * updatedAt}. A PENDING action past its expiry is reported as EXPIRED.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActionResponse(Long id, Long conversationId, String action, String summary, JsonNode preview, String status,
                             LocalDateTime expiresAt, JsonNode result, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
