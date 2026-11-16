package com.recruitpro.assistant.dto;

import java.time.LocalDateTime;
import java.util.List;

/** GET /api/v1/assistant/conversations/{id}: {id, title, messages}, oldest message first. */
public record ConversationResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt,
                                   List<MessageResponse> messages) {
}
