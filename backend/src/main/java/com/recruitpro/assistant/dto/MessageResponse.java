package com.recruitpro.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * Message (ASSISTANT_PLAN 3.2): {id, role "user"|"assistant", text, blocks [Block], status?, planner, createdAt}.
 * {@code status}: the short trace of the tool steps ("Looked up job insights · ..."), assistant messages only;
 * {@code planner}: "offline", "gemini", ..., "unavailable" (null for user messages). Nulls are serialised.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageResponse(Long id, String role, String text, JsonNode blocks, String status, String planner,
                              LocalDateTime createdAt) {
}
