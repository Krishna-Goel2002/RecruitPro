package com.recruitpro.assistant.dto;

import java.time.LocalDateTime;

/** One row of GET /api/v1/assistant/conversations (the caller's own, most recently active first). */
public record ConversationSummaryResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
