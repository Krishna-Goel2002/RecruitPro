package com.recruitpro.assistant.controller;

import com.recruitpro.assistant.dto.CancelActionResponse;
import com.recruitpro.assistant.dto.ConfirmActionRequest;
import com.recruitpro.assistant.dto.ConfirmActionResponse;
import com.recruitpro.assistant.dto.ConversationCreatedResponse;
import com.recruitpro.assistant.dto.ConversationResponse;
import com.recruitpro.assistant.dto.ConversationSummaryResponse;
import com.recruitpro.assistant.dto.SendMessageRequest;
import com.recruitpro.assistant.dto.SendMessageResponse;
import com.recruitpro.assistant.service.AssistantActionService;
import com.recruitpro.assistant.service.AssistantConversationService;
import com.recruitpro.assistant.service.AssistantOrchestrator;
import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR assistant (ASSISTANT_PLAN 3.2): conversations, messages and proposed actions of the signed-in staff user (every
 * staff role; the tools each role sees differ). Responses use the usual {success, message, data} envelope.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@PreAuthorize("hasAnyRole('ADMIN','HR_MANAGER','RECRUITER','INTERVIEWER')")
public class AssistantController {

    private final AssistantConversationService conversationService;
    private final AssistantOrchestrator orchestrator;
    private final AssistantActionService actionService;

    public AssistantController(AssistantConversationService conversationService, AssistantOrchestrator orchestrator,
                               AssistantActionService actionService) {
        this.conversationService = conversationService;
        this.orchestrator = orchestrator;
        this.actionService = actionService;
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationCreatedResponse> create() {
        return ApiResponse.success(conversationService.create(), "Conversation started");
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationSummaryResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(conversationService.list(page, size));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationResponse> get(@PathVariable Long id) {
        return ApiResponse.success(conversationService.get(id));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<SendMessageResponse> send(@PathVariable Long id, @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.success(orchestrator.send(id, request));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return ApiResponse.success("Conversation deleted");
    }

    @PostMapping("/actions/{actionId}/confirm")
    public ApiResponse<ConfirmActionResponse> confirm(@PathVariable Long actionId,
                                                      @Valid @RequestBody(required = false) ConfirmActionRequest request) {
        return ApiResponse.success(actionService.confirm(actionId, request));
    }

    @PostMapping("/actions/{actionId}/cancel")
    public ApiResponse<CancelActionResponse> cancel(@PathVariable Long actionId) {
        return ApiResponse.success(actionService.cancel(actionId));
    }
}
