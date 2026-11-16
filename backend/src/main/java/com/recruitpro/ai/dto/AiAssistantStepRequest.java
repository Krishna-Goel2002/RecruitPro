package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/assistant/step request (ASSISTANT_PLAN 3.1). Everything in it is de-identified by the backend:
 * candidates appear only as application codes ("A-3014"); names, e-mails, phones and URLs never do.
 * {@code draft} is serialised as {@code null} when there is no slot-filling state.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiAssistantStepRequest(List<Message> messages, Map<String, Object> context, List<Map<String, Object>> tools,
                                     List<Map<String, Object>> observations, Map<String, Object> draft,
                                     boolean allowExternalModel) {

    /** One conversation message: role "user" or "assistant". */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Message(String role, String text) { }
}
