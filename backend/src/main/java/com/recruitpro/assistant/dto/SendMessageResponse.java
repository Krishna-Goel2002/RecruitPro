package com.recruitpro.assistant.dto;

import java.util.List;

/** The new messages of one exchange: the user's message and the assistant's reply, in that order. */
public record SendMessageResponse(List<MessageResponse> messages) {
}
