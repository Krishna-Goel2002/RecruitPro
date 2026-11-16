package com.recruitpro.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/assistant/conversations/{id}/messages: {text (1..2000), context?}. */
public record SendMessageRequest(@NotBlank @Size(max = 2000) String text, @Valid MessageContext context) {
}
