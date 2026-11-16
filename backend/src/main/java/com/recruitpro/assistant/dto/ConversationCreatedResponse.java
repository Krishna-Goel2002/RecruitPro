package com.recruitpro.assistant.dto;

import java.time.LocalDateTime;

/** POST /api/v1/assistant/conversations: {id, createdAt}. */
public record ConversationCreatedResponse(Long id, LocalDateTime createdAt) {
}
