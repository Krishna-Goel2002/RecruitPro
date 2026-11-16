package com.recruitpro.assistant.dto;

/** POST …/confirm: {action, result, message} — the assistant message added to the conversation. */
public record ConfirmActionResponse(ActionResponse action, ActionResult result, MessageResponse message) {
}
