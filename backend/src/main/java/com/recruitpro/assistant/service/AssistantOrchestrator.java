package com.recruitpro.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.recruitpro.ai.dto.AiAssistantStepRequest;
import com.recruitpro.ai.dto.AiAssistantStepResponse;
import com.recruitpro.assistant.AssistantSettings;
import com.recruitpro.assistant.dto.MessageContext;
import com.recruitpro.assistant.dto.SendMessageRequest;
import com.recruitpro.assistant.dto.SendMessageResponse;
import com.recruitpro.assistant.entity.AssistantAction;
import com.recruitpro.assistant.entity.AssistantActionStatus;
import com.recruitpro.assistant.entity.AssistantConversation;
import com.recruitpro.assistant.entity.AssistantMessage;
import com.recruitpro.assistant.repository.AssistantActionRepository;
import com.recruitpro.assistant.repository.AssistantConversationRepository;
import com.recruitpro.assistant.repository.AssistantMessageRepository;
import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.tool.ActionProposal;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ApplicationCodes;
import com.recruitpro.assistant.tool.AssistantTool;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.ToolRegistry;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.assistant.tool.WriteTool;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.user.entity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The assistant's orchestration loop (ASSISTANT_PLAN 2 and 3; AS-BE). For one user message: store it, then call the
 * stateless planner at most {@code max-steps} (6) times within {@code message-budget-ms} (60 s), each time with the last
 * 20 messages (de-identified), the page context, the user's tool catalogue, this turn's observations, the job-posting
 * draft and the organisation's {@code allowExternalModel}. A {@code tool_call} of a read tool runs the tool as the user
 * and adds a de-identified observation; a write tool (or a {@code propose}) becomes a PENDING proposed action with a
 * preview; {@code ask} and {@code answer} end the turn. Planner blocks are validated and enriched by
 * {@link BlockAssembler}. When the planner is unavailable the reply is an offline-safe notice with links to the
 * portal pages. Not transactional: nothing is held open while the planner works; every write is its own transaction.
 */
@Service
public class AssistantOrchestrator {

    static final Set<String> PAGES = Set.of("job", "applicant", "jobs", "admin", "interviews", "other");
    static final int KNOWN_NAMES_LIMIT = 2000;
    static final int MAX_TEXT = 8000;
    static final String UNAVAILABLE_PLANNER = "unavailable";

    private static final Logger log = LoggerFactory.getLogger(AssistantOrchestrator.class);

    private final AssistantConversationService conversations;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final AssistantActionRepository actionRepository;
    private final OrganizationRepository organizationRepository;
    private final ToolRegistry registry;
    private final AssistantPlanner planner;
    private final BlockAssembler blocks;
    private final AssistantRateLimiter rateLimiter;
    private final ApplicationAccess access;
    private final HrJobAccessService jobAccess;
    private final AssistantSettings settings;
    private final AssistantJson json;

    public AssistantOrchestrator(AssistantConversationService conversations,
                                 AssistantConversationRepository conversationRepository,
                                 AssistantMessageRepository messageRepository, AssistantActionRepository actionRepository,
                                 OrganizationRepository organizationRepository, ToolRegistry registry,
                                 AssistantPlanner planner, BlockAssembler blocks, AssistantRateLimiter rateLimiter,
                                 ApplicationAccess access, HrJobAccessService jobAccess, AssistantSettings settings,
                                 AssistantJson json) {
        this.conversations = conversations;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.actionRepository = actionRepository;
        this.organizationRepository = organizationRepository;
        this.registry = registry;
        this.planner = planner;
        this.blocks = blocks;
        this.rateLimiter = rateLimiter;
        this.access = access;
        this.jobAccess = jobAccess;
        this.settings = settings;
        this.json = json;
    }

    /** The assistant's reply to one message: text, blocks, planner name. */
    record Reply(String text, ArrayNode blocks, String planner) { }

    /** POST /api/v1/assistant/conversations/{id}/messages. */
    public SendMessageResponse send(Long conversationId, SendMessageRequest request) {
        CurrentUser user = conversations.requireStaff();
        AssistantConversation conversation = conversations.requireOwned(conversationId, user);
        String text = request == null || request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw new BadRequestException("text is required");
        }
        rateLimiter.acquire(user.userId());
        long deadline = System.nanoTime() + settings.messageBudgetMs() * 1_000_000L;
        AssistantMessage userMessage = conversations.append(conversation.getId(), AssistantMessage.ROLE_USER, text,
                AssistantJson.emptyArray(), null, null);

        Deidentifier deid = deidentifier(user);
        ToolContext toolContext = new ToolContext(user, deid);
        MessageContext pageContext = request.context();
        Map<String, Object> context = context(user, pageContext, toolContext);
        List<Map<String, Object>> catalogue = new ArrayList<>();
        for (AssistantTool tool : registry.catalogueFor(user)) {
            catalogue.add(tool.catalogueEntry());
        }
        boolean allowExternalModel = allowExternalModel(user);
        Map<String, Object> draft = draft(conversation.getDraftJson());
        List<AiAssistantStepRequest.Message> history = history(conversation.getId(), deid);
        List<Map<String, Object>> observations = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        String plannerName = null;
        Reply reply = null;

        for (int step = 0; step < settings.maxSteps() && reply == null; step++) {
            AiAssistantStepRequest stepRequest = new AiAssistantStepRequest(history, context, catalogue,
                    List.copyOf(observations), draft == null ? null : scrubMap(deid, draft), allowExternalModel);
            AiAssistantStepResponse response;
            try {
                response = planner.step(stepRequest, remainingMs(deadline));
            } catch (PlannerUnavailableException ex) {
                reply = unavailable(user, pageContext, context, ex.isTimedOut());
                break;
            }
            plannerName = plannerName(response.meta());
            switch (response.type()) {
                case "tool_call" -> {
                    AssistantTool tool = registry.find(response.tool(), user).orElse(null);
                    if (tool == null) {
                        observations.add(failed(deid, response.tool(), response.args(),
                                "Unknown tool, or not available to this user; use only the tools of the catalogue"));
                        trace.add("Skipped an unknown tool");
                    } else if (tool instanceof WriteTool writeTool) {
                        reply = propose(writeTool, response.args(), null, conversation, toolContext, plannerName);
                    } else if (remainingMs(deadline) <= 0) {
                        reply = timedOut(plannerName);
                    } else {
                        observations.add(run((ReadTool) tool, response.args(), toolContext));
                        trace.add(thought(response.thought(), tool.name()));
                    }
                }
                case "ask" -> {
                    draft = updateDraft(conversation.getId(), response.draft(), draft);
                    reply = ask(response, plannerName);
                }
                case "propose" -> {
                    draft = updateDraft(conversation.getId(), response.draft(), draft);
                    AssistantTool tool = registry.find(response.action(), user).orElse(null);
                    reply = tool instanceof WriteTool writeTool
                            ? propose(writeTool, response.args(), response.summary(), conversation, toolContext, plannerName)
                            : notice(plannerName, "I can't do that for you: it is not an action available to your role.",
                            "warning");
                }
                case "answer" -> reply = answer(user, response, plannerName);
                default -> reply = unavailable(user, pageContext, context, false);
            }
        }
        if (reply == null) {
            reply = notice(plannerName, "I could not finish this within " + settings.maxSteps()
                    + " steps. Try a more specific question.", "warning");
        }
        AssistantMessage assistantMessage = conversations.append(conversation.getId(), AssistantMessage.ROLE_ASSISTANT,
                reply.text(), reply.blocks(), reply.planner(), String.join(" · ", trace));
        return new SendMessageResponse(conversations.toResponses(List.of(userMessage, assistantMessage)));
    }

    // ---- request parts ----

    /**
     * A de-identifier that knows every candidate whose identity a member of the organisation can know (revealed
     * applications, or all when blind review is off): names a user could type are stripped before the planner sees them.
     */
    private Deidentifier deidentifier(CurrentUser user) {
        Deidentifier deid = new Deidentifier();
        boolean blind = organizationRepository.findById(user.organizationId()).map(Organization::isBlindReview).orElse(true);
        for (Object[] row : conversationRepository.findKnownCandidateNames(user.organizationId(), !blind,
                PageRequest.of(0, KNOWN_NAMES_LIMIT))) {
            deid.addPerson((String) row[0], (String) row[1], (String) row[2]);
        }
        return deid;
    }

    /** {page, role, jobId?, jobTitle?, applicationCode?}; ids the user may not see are left out. */
    private Map<String, Object> context(CurrentUser user, MessageContext pageContext, ToolContext toolContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        String page = pageContext == null || pageContext.page() == null ? "other"
                : pageContext.page().trim().toLowerCase(Locale.ROOT);
        context.put("page", PAGES.contains(page) ? page : "other");
        context.put("role", user.role().name());
        boolean reader = user.hasAnyRole(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);
        Long jobId = pageContext == null ? null : pageContext.jobId();
        Long applicationId = pageContext == null ? null : pageContext.applicationId();
        if (applicationId != null) {
            try {
                AppInfo info = reader ? access.requireReadable(toolContext, applicationId)
                        : access.interviewedApplicationIds().contains(applicationId)
                        ? access.requireInOrganization(toolContext, applicationId) : null;
                if (info != null) {
                    context.put("applicationCode", info.code());
                    if (jobId == null && reader) {
                        jobId = info.jobId();
                    }
                }
            } catch (RuntimeException notVisible) {
                log.debug("Assistant context application not visible: {}", notVisible.getClass().getSimpleName());
            }
        }
        if (jobId != null && reader) {
            try {
                Job job = jobAccess.requireJobReadAccess(jobId);
                context.put("jobId", job.getId());
                context.put("jobTitle", job.getTitle());
            } catch (RuntimeException notVisible) {
                log.debug("Assistant context job not visible: {}", notVisible.getClass().getSimpleName());
            }
        }
        return context;
    }

    private boolean allowExternalModel(CurrentUser user) {
        return organizationRepository.findById(user.organizationId())
                .map(Organization::isAssistantExternalModel).orElse(false);
    }

    private List<AiAssistantStepRequest.Message> history(Long conversationId, Deidentifier deid) {
        List<AssistantMessage> recent = new ArrayList<>(messageRepository.findByConversationIdOrderByIdDesc(conversationId,
                PageRequest.of(0, settings.historyMessages())));
        Collections.reverse(recent);
        List<AiAssistantStepRequest.Message> history = new ArrayList<>();
        for (AssistantMessage message : recent) {
            history.add(new AiAssistantStepRequest.Message(message.getRole(), deid.scrub(message.getText())));
        }
        return history;
    }

    private Map<String, Object> draft(String draftJson) {
        Map<String, Object> draft = json.readMap(draftJson);
        return draft.isEmpty() ? null : draft;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scrubMap(Deidentifier deid, Map<String, Object> map) {
        return (Map<String, Object>) deid.scrubTree(map);
    }

    /** The planner's draft as {kind: "job_posting", fields: {...}}; stored on the conversation. */
    private Map<String, Object> updateDraft(Long conversationId, Map<String, Object> proposed, Map<String, Object> current) {
        if (proposed == null || proposed.isEmpty()) {
            return current;
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        if (proposed.get("fields") instanceof Map<?, ?>) {
            draft.put("kind", proposed.get("kind") instanceof String kind ? kind : "job_posting");
            draft.put("fields", proposed.get("fields"));
        } else {
            draft.put("kind", "job_posting");
            draft.put("fields", proposed);
        }
        if (json.write(draft).length() > 60_000) {
            return current;
        }
        conversations.saveDraft(conversationId, draft);
        return draft;
    }

    // ---- steps ----

    /** Runs a read tool as the user; the observation is de-identified (names of every touched candidate removed). */
    private Map<String, Object> run(ReadTool tool, Map<String, Object> rawArgs, ToolContext context) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("tool", tool.name());
        try {
            ToolArgs args = ToolArgs.validate(tool.args(), rawArgs);
            observation.put("args", args.values());
            Map<String, Object> data = tool.run(context, args);
            observation.put("ok", true);
            observation.put("data", data);
        } catch (RuntimeException ex) {
            if (!AssistantErrors.expected(ex)) {
                log.warn("Assistant tool {} failed: {}", tool.name(), ex.getClass().getSimpleName());
            }
            observation.put("args", rawArgs == null ? Map.of() : rawArgs);
            observation.put("ok", false);
            observation.put("error", AssistantErrors.message(ex));
        }
        return scrubMap(context.deidentifier(), observation);
    }

    private Map<String, Object> failed(Deidentifier deid, String tool, Map<String, Object> rawArgs, String error) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("tool", tool == null ? "" : BlockAssembler.text(tool, 60));
        observation.put("args", rawArgs == null ? Map.of() : rawArgs);
        observation.put("ok", false);
        observation.put("error", error);
        return scrubMap(deid, observation);
    }

    /**
     * A write: validated and prepared as the user (nothing changes), stored as a PENDING action expiring after
     * {@code action-ttl-minutes}, shown with its preview (and the job preview for create_job). A refusal ends the turn
     * with a notice.
     */
    private Reply propose(WriteTool tool, Map<String, Object> rawArgs, String plannerSummary,
                          AssistantConversation conversation, ToolContext context, String plannerName) {
        ActionProposal proposal;
        try {
            proposal = tool.prepare(context, ToolArgs.validate(tool.args(), rawArgs));
        } catch (RuntimeException ex) {
            if (!AssistantErrors.expected(ex)) {
                log.warn("Assistant action {} could not be prepared: {}", tool.name(), ex.getClass().getSimpleName());
            }
            return notice(plannerName, "I can't propose that: " + AssistantErrors.message(ex), "warning");
        }
        CurrentUser user = context.user();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("summary", proposal.summary());
        preview.putAll(proposal.preview());
        AssistantAction action = new AssistantAction();
        action.setConversationId(conversation.getId());
        action.setUserId(user.userId());
        action.setOrganizationId(user.organizationId());
        action.setAction(tool.name());
        action.setArgsJson(json.write(proposal.args()));
        action.setPreviewJson(json.write(preview));
        action.setStatus(AssistantActionStatus.PENDING);
        action.setExpiresAt(UtcTime.now().plusMinutes(settings.actionTtlMinutes()));
        AssistantAction saved = actionRepository.saveAndFlush(action);

        List<JsonNode> nodes = new ArrayList<>();
        for (Map<String, Object> extra : proposal.extraBlocks()) {
            nodes.add(json.toNode(extra));
        }
        nodes.add(blocks.action(saved.getId(), tool.name(), proposal.summary(), json.toNode(preview),
                AssistantActionStatus.PENDING.name(), saved.getExpiresAt()));
        String text = plannerSummary != null && !plannerSummary.isBlank()
                ? limit(plannerSummary.trim(), MAX_TEXT)
                : proposal.summary() + ". Nothing changes until you confirm.";
        return new Reply(text, BlockAssembler.blocks(nodes), plannerName);
    }

    private Reply ask(AiAssistantStepResponse response, String plannerName) {
        String text = response.text() == null || response.text().isBlank() ? "Could you tell me more?"
                : limit(response.text().trim(), MAX_TEXT);
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(blocks.question(BlockAssembler.text(response.field(), 60), BlockAssembler.text(text, 1000),
                response.options()));
        return new Reply(text, BlockAssembler.blocks(nodes), plannerName);
    }

    private Reply answer(CurrentUser user, AiAssistantStepResponse response, String plannerName) {
        ArrayNode assembled = blocks.fromPlanner(user, response.blocks());
        String text = response.text() == null ? "" : limit(response.text().trim(), MAX_TEXT);
        if (text.isEmpty() && assembled.isEmpty()) {
            text = "I don't have an answer to that. Try asking about a job, an applicant (by code, e.g. A-3014) or "
                    + "what needs your attention.";
        }
        return new Reply(text, assembled, plannerName);
    }

    /**
     * Offline-safe reply when the planner cannot be reached: everything the assistant does is also on the normal
     * screens, so it links to them.
     */
    private Reply unavailable(CurrentUser user, MessageContext pageContext, Map<String, Object> context, boolean timedOut) {
        String text = timedOut
                ? "That took longer than the assistant's time limit. Please try again, or use the portal pages below."
                : "The assistant is unavailable right now. You can still do everything from the normal screens.";
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(blocks.notice("warning", timedOut
                ? "The assistant stopped after its time limit; nothing was changed."
                : "The assistant is unavailable; use the portal pages instead. Nothing was changed."));
        boolean reader = user.hasAnyRole(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);
        if (reader) {
            Object jobId = context.get("jobId");
            Long applicationId = ApplicationCodes.parse(context.get("applicationCode"));
            if (jobId instanceof Long id && applicationId != null) {
                nodes.add(blocks.link("Open this applicant", Tools.applicantLink(id, applicationId)));
            }
            if (jobId instanceof Long id) {
                nodes.add(blocks.link("Open this job's applicants", Tools.jobLink(id)));
            }
            nodes.add(blocks.link("Jobs", "/jobs"));
        }
        nodes.add(blocks.link("My interviews", "/interviews"));
        if (user.hasRole(Role.ADMIN)) {
            nodes.add(blocks.link("Administration", "/admin"));
        }
        return new Reply(text, BlockAssembler.blocks(nodes), UNAVAILABLE_PLANNER);
    }

    private Reply timedOut(String plannerName) {
        return notice(plannerName, "That took longer than the assistant's time limit. Please try again.", "warning");
    }

    private Reply notice(String plannerName, String text, String tone) {
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(blocks.notice(tone, text));
        return new Reply(text, BlockAssembler.blocks(nodes), plannerName);
    }

    // ---- helpers ----

    private static long remainingMs(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    /** meta.planner (+ " (fallback)" when the planner fell back to offline rules). */
    static String plannerName(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        String name = BlockAssembler.text(meta.get("planner"), 25);
        if (name == null) {
            return null;
        }
        return Boolean.TRUE.equals(meta.get("fallback")) ? name + " (fallback)" : name;
    }

    private static String thought(String thought, String tool) {
        String text = BlockAssembler.text(thought, 120);
        return text != null ? text : "Ran " + tool;
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
