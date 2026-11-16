package com.recruitpro.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.assistant.dto.ActionResult;
import com.recruitpro.assistant.dto.CancelActionResponse;
import com.recruitpro.assistant.dto.ConfirmActionRequest;
import com.recruitpro.assistant.dto.ConfirmActionResponse;
import com.recruitpro.assistant.entity.AssistantAction;
import com.recruitpro.assistant.entity.AssistantActionStatus;
import com.recruitpro.assistant.entity.AssistantMessage;
import com.recruitpro.assistant.repository.AssistantActionRepository;
import com.recruitpro.assistant.tool.ActionOutcome;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.ToolRegistry;
import com.recruitpro.assistant.tool.WriteTool;
import com.recruitpro.assistant.tool.write.CreateJobTool;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Confirm and cancel of proposed actions (ASSISTANT_PLAN 3.2). Only the user who was proposed the action may act on it
 * (anything else is 404). Confirm claims the PENDING action atomically (a second confirm, or one after a cancel or the
 * 30-minute expiry, is 409), then re-validates the stored arguments against the tool's schema and the user's current
 * role, and runs the tool's {@code execute} as the user through the existing services, which check permissions and
 * the current state again. A refusal marks the action FAILED with the reason (nothing changed); a success is audited
 * ({@code ASSISTANT_ACTION_CONFIRMED}, details starting with {@code via=ASSISTANT}) in the same transaction as the change
 * (AI drafting tools: right after it). Either way an assistant message is added to the conversation.
 */
@Service
public class AssistantActionService {

    private static final Logger log = LoggerFactory.getLogger(AssistantActionService.class);

    private final AssistantConversationService conversations;
    private final AssistantActionRepository actionRepository;
    private final ToolRegistry registry;
    private final BlockAssembler blocks;
    private final AssistantJson json;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    public AssistantActionService(AssistantConversationService conversations, AssistantActionRepository actionRepository,
                                  ToolRegistry registry, BlockAssembler blocks, AssistantJson json,
                                  AuditService auditService, PlatformTransactionManager transactionManager) {
        this.conversations = conversations;
        this.actionRepository = actionRepository;
        this.registry = registry;
        this.blocks = blocks;
        this.json = json;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** POST /api/v1/assistant/actions/{id}/confirm. */
    public ConfirmActionResponse confirm(Long actionId, ConfirmActionRequest request) {
        CurrentUser user = conversations.requireStaff();
        AssistantAction action = requireOwned(actionId, user);
        claim(action, AssistantActionStatus.CONFIRMED);

        String mode = request == null || request.mode() == null ? null : request.mode().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> args = json.readMap(action.getArgsJson());
        if (CreateJobTool.NAME.equals(action.getAction()) && mode != null) {
            args.put("publish", "PUBLISH".equals(mode));
        }
        ToolContext context = new ToolContext(user, new Deidentifier());
        ActionOutcome outcome = null;
        String error = null;
        try {
            WriteTool tool = registry.find(action.getAction(), user)
                    .filter(found -> found instanceof WriteTool).map(found -> (WriteTool) found)
                    .orElseThrow(() -> new AssistantToolException("You are no longer allowed to do this"));
            ToolArgs validated = ToolArgs.validate(tool.args(), args);
            Map<String, Object> shown = json.readMap(action.getPreviewJson());
            if (tool.transactional()) {
                outcome = transactions.execute(status -> {
                    tool.checkUnchanged(context, validated, shown);
                    ActionOutcome result = tool.execute(context, validated);
                    audit(action, result, validated);
                    return result;
                });
            } else {
                tool.checkUnchanged(context, validated, shown);
                ActionOutcome result = tool.execute(context, validated);
                transactions.executeWithoutResult(status -> audit(action, result, validated));
                outcome = result;
            }
        } catch (RuntimeException ex) {
            if (!AssistantErrors.expected(ex)) {
                log.warn("Assistant action #{} ({}) failed: {}", action.getId(), action.getAction(), ex.getClass().getSimpleName());
            }
            outcome = null;
            error = AssistantErrors.message(ex);
        }

        ActionResult result = outcome == null
                ? new ActionResult("failed", null, null, error)
                : new ActionResult(outcome.kind(), outcome.id(), outcome.link(), null);
        AssistantActionStatus finalStatus = outcome == null ? AssistantActionStatus.FAILED : AssistantActionStatus.CONFIRMED;
        AssistantAction saved = transactions.execute(status -> {
            AssistantAction fresh = actionRepository.findById(action.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Action not found"));
            fresh.setStatus(finalStatus);
            fresh.setResultJson(json.write(resultMap(result)));
            return actionRepository.saveAndFlush(fresh);
        });
        if (outcome != null && CreateJobTool.NAME.equals(action.getAction())) {
            conversations.saveDraft(action.getConversationId(), null);
        }

        JsonNode preview = json.read(action.getPreviewJson(), AssistantJson.object());
        String summary = preview.path("summary").isTextual() ? preview.path("summary").asText() : action.getAction();
        List<JsonNode> nodes = new ArrayList<>();
        String text;
        if (outcome != null) {
            text = outcome.message();
            nodes.add(blocks.notice("success", outcome.message()));
            nodes.add(blocks.link("Open", outcome.link()));
        } else {
            text = "I couldn't do that: " + error + " Nothing was changed.";
            nodes.add(blocks.notice("warning", text));
        }
        nodes.add(blocks.action(saved.getId(), saved.getAction(), summary, preview, finalStatus.name(), saved.getExpiresAt()));
        AssistantMessage message = conversations.append(action.getConversationId(), AssistantMessage.ROLE_ASSISTANT, text,
                BlockAssembler.blocks(nodes), null, null);
        return new ConfirmActionResponse(conversations.toResponse(saved, user), result, conversations.toResponse(message, user));
    }

    /** POST /api/v1/assistant/actions/{id}/cancel. */
    public CancelActionResponse cancel(Long actionId) {
        CurrentUser user = conversations.requireStaff();
        AssistantAction action = requireOwned(actionId, user);
        claim(action, AssistantActionStatus.CANCELLED);
        AssistantAction saved = actionRepository.findById(action.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Action not found"));
        return new CancelActionResponse(conversations.toResponse(saved, user));
    }

    private AssistantAction requireOwned(Long actionId, CurrentUser user) {
        if (actionId == null) {
            throw new ResourceNotFoundException("Action not found");
        }
        return actionRepository.findByIdAndUserId(actionId, user.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Action not found"));
    }

    /** PENDING &rarr; {@code to} for exactly one caller; 409 when it is no longer pending or has expired. */
    private void claim(AssistantAction action, AssistantActionStatus to) {
        LocalDateTime now = UtcTime.now();
        if (action.isExpired(now)) {
            actionRepository.expire(action.getId(), AssistantActionStatus.PENDING, AssistantActionStatus.EXPIRED, now);
            throw conflict("This proposed action expired; ask the assistant again");
        }
        if (action.getStatus() != AssistantActionStatus.PENDING) {
            throw conflict("This action was already " + action.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (actionRepository.claim(action.getId(), AssistantActionStatus.PENDING, to, now) != 1) {
            throw conflict("This action was already handled or has expired");
        }
    }

    private void audit(AssistantAction action, ActionOutcome outcome, ToolArgs args) {
        String mode = CreateJobTool.NAME.equals(action.getAction())
                ? "; mode=" + (args.getBoolean("publish", false) ? "PUBLISH" : "DRAFT") : "";
        auditService.record(AuditActions.ASSISTANT_ACTION_CONFIRMED, outcome.auditTargetType(), outcome.auditTargetId(),
                "via=ASSISTANT; action=" + action.getAction() + "; assistantActionId=" + action.getId()
                        + "; conversationId=" + action.getConversationId() + mode + "; " + outcome.auditDetails());
    }

    private static Map<String, Object> resultMap(ActionResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", result.kind());
        if (result.id() != null) map.put("id", result.id());
        if (result.link() != null) map.put("link", result.link());
        if (result.error() != null) map.put("error", result.error());
        return map;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
