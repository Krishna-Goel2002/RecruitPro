package com.recruitpro.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * A proposed action, shaped as the action block it updates: {kind: "action", actionId, id (= actionId), conversationId,
 * action (tool name), summary, preview, status PENDING|CONFIRMED|CANCELLED|EXPIRED|FAILED, expiresAt, result ({kind, id,
 * link} or {kind: "failed", error}; null while pending), error (the reason of a FAILED action, else null), createdAt,
 * updatedAt}. A PENDING action past its expiry is reported as EXPIRED.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ActionResponse(String kind, Long actionId, Long id, Long conversationId, String action, String summary,
                             JsonNode preview, String status, LocalDateTime expiresAt, JsonNode result, String error,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
}
