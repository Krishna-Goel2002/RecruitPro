package com.recruitpro.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recruitpro.assistant.dto.ActionResponse;
import com.recruitpro.assistant.dto.ConversationCreatedResponse;
import com.recruitpro.assistant.dto.ConversationResponse;
import com.recruitpro.assistant.dto.ConversationSummaryResponse;
import com.recruitpro.assistant.dto.MessageResponse;
import com.recruitpro.assistant.entity.AssistantAction;
import com.recruitpro.assistant.entity.AssistantActionStatus;
import com.recruitpro.assistant.entity.AssistantConversation;
import com.recruitpro.assistant.entity.AssistantMessage;
import com.recruitpro.assistant.repository.AssistantActionRepository;
import com.recruitpro.assistant.repository.AssistantConversationRepository;
import com.recruitpro.assistant.repository.AssistantMessageRepository;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conversations of the signed-in staff user (ASSISTANT_PLAN 3.2): create, list, read, delete, and the message/action
 * views. Ownership: a conversation or action of anybody else (same organisation included) is 404.
 */
@Service
public class AssistantConversationService {

    static final int MAX_PAGE_SIZE = 50;
    static final int TITLE_LENGTH = 80;

    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final AssistantActionRepository actionRepository;
    private final SecurityUtils securityUtils;
    private final AssistantJson json;

    public AssistantConversationService(AssistantConversationRepository conversationRepository,
                                        AssistantMessageRepository messageRepository,
                                        AssistantActionRepository actionRepository, SecurityUtils securityUtils,
                                        AssistantJson json) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.actionRepository = actionRepository;
        this.securityUtils = securityUtils;
        this.json = json;
    }

    /** The caller, who must be staff of an organisation (every staff role may use the assistant). */
    public CurrentUser requireStaff() {
        CurrentUser user = securityUtils.requireOrganizationMember();
        if (!user.isStaff()) {
            throw new ForbiddenException("The assistant is available to staff only");
        }
        return user;
    }

    @Transactional
    public ConversationCreatedResponse create() {
        CurrentUser user = requireStaff();
        AssistantConversation conversation = new AssistantConversation();
        conversation.setUserId(user.userId());
        conversation.setOrganizationId(user.organizationId());
        conversation.setTitle(AssistantConversation.DEFAULT_TITLE);
        AssistantConversation saved = conversationRepository.saveAndFlush(conversation);
        return new ConversationCreatedResponse(saved.getId(), saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationSummaryResponse> list(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        CurrentUser user = requireStaff();
        Page<AssistantConversation> conversations = conversationRepository.findAllByUserId(user.userId(),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        List<ConversationSummaryResponse> content = conversations.getContent().stream()
                .map(c -> new ConversationSummaryResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()))
                .toList();
        return PageResponse.<ConversationSummaryResponse>builder().content(content)
                .page(conversations.getNumber()).size(conversations.getSize())
                .totalElements(conversations.getTotalElements()).totalPages(conversations.getTotalPages())
                .first(conversations.isFirst()).last(conversations.isLast()).build();
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(Long conversationId) {
        CurrentUser user = requireStaff();
        AssistantConversation conversation = requireOwned(conversationId, user);
        List<AssistantMessage> messages = messageRepository.findAllByConversationIdOrderByIdAsc(conversation.getId());
        return new ConversationResponse(conversation.getId(), conversation.getTitle(), conversation.getCreatedAt(),
                conversation.getUpdatedAt(), toResponses(messages));
    }

    @Transactional
    public void delete(Long conversationId) {
        CurrentUser user = requireStaff();
        AssistantConversation conversation = requireOwned(conversationId, user);
        conversationRepository.delete(conversation);
    }

    /** The user's own conversation, otherwise 404. */
    public AssistantConversation requireOwned(Long conversationId, CurrentUser user) {
        if (conversationId == null) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return conversationRepository.findByIdAndUserId(conversationId, user.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    /** Appends a message and marks the conversation active (and titles it after its first user message). */
    @Transactional
    public AssistantMessage append(Long conversationId, String role, String text, ArrayNode blocks, String planner,
                                   String trace) {
        AssistantMessage message = new AssistantMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setText(text == null ? "" : text);
        message.setBlocksJson(blocks == null ? "[]" : json.write(blocks));
        message.setPlanner(planner == null ? null : limit(planner, 40));
        message.setStatusTrace(trace == null || trace.isBlank() ? null : limit(trace, 1000));
        message.setCreatedAt(UtcTime.now());
        AssistantMessage saved = messageRepository.saveAndFlush(message);
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            if (AssistantMessage.ROLE_USER.equals(role) && AssistantConversation.DEFAULT_TITLE.equals(conversation.getTitle())
                    && text != null && !text.isBlank()) {
                conversation.setTitle(limit(text.trim().replaceAll("\\s+", " "), TITLE_LENGTH));
            }
            conversation.setUpdatedAt(UtcTime.now());
            conversationRepository.save(conversation);
        });
        return saved;
    }

    /** Stores the slot-filling draft of the conversation (null clears it). */
    @Transactional
    public void saveDraft(Long conversationId, Map<String, Object> draft) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.setDraftJson(draft == null || draft.isEmpty() ? null : json.write(draft));
            conversationRepository.save(conversation);
        });
    }

    public List<MessageResponse> toResponses(List<AssistantMessage> messages) {
        Set<Long> actionIds = new LinkedHashSet<>();
        List<ArrayNode> blocks = new ArrayList<>();
        for (AssistantMessage message : messages) {
            ArrayNode array = json.readArray(message.getBlocksJson());
            blocks.add(array);
            for (JsonNode block : array) {
                if ("action".equals(block.path("kind").asText()) && block.path("actionId").canConvertToLong()) {
                    actionIds.add(block.path("actionId").asLong());
                }
            }
        }
        Map<Long, AssistantAction> actions = new HashMap<>();
        if (!actionIds.isEmpty()) {
            for (AssistantAction action : actionRepository.findAllByIdIn(actionIds)) {
                actions.put(action.getId(), action);
            }
        }
        LocalDateTime now = UtcTime.now();
        List<MessageResponse> responses = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            AssistantMessage message = messages.get(i);
            ArrayNode array = blocks.get(i);
            for (JsonNode block : array) {
                if (block instanceof ObjectNode object && "action".equals(object.path("kind").asText())) {
                    AssistantAction action = actions.get(object.path("actionId").asLong());
                    if (action != null) {
                        object.put("status", effectiveStatus(action, now).name());
                    }
                }
            }
            responses.add(new MessageResponse(message.getId(), message.getRole(), message.getText(), array,
                    message.getStatusTrace(), message.getPlanner(), message.getCreatedAt()));
        }
        return responses;
    }

    public MessageResponse toResponse(AssistantMessage message) {
        return toResponses(List.of(message)).get(0);
    }

    public ActionResponse toResponse(AssistantAction action) {
        JsonNode preview = json.read(action.getPreviewJson(), AssistantJson.object());
        String summary = preview.path("summary").isTextual() ? preview.path("summary").asText() : action.getAction();
        return new ActionResponse(action.getId(), action.getConversationId(), action.getAction(), summary, preview,
                effectiveStatus(action, UtcTime.now()).name(), action.getExpiresAt(), json.read(action.getResultJson(), null),
                action.getCreatedAt(), action.getUpdatedAt());
    }

    /** PENDING actions past their expiry are EXPIRED. */
    public static AssistantActionStatus effectiveStatus(AssistantAction action, LocalDateTime now) {
        return action.isExpired(now) ? AssistantActionStatus.EXPIRED : action.getStatus();
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
