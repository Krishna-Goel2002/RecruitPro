package com.recruitpro.assistant.tool.write;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.service.ApplicationTransitions;
import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.tool.ActionOutcome;
import com.recruitpro.assistant.tool.ActionProposal;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.assistant.tool.WriteTool;
import com.recruitpro.hr.dto.DecisionRequest;
import com.recruitpro.hr.service.ApplicationDecisionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * decide_application: shortlist, reject (a reason is required), move to interview or select, through
 * ApplicationDecisionService.decide on confirm (which locks the row, checks the state machine again, records history,
 * audits APPLICATION_DECISION and notifies the candidate). A state that changed in between makes the confirm FAILED.
 */
@Component
public class DecideApplicationTool implements WriteTool {

    static final List<String> DECISIONS = List.of("SHORTLIST", "REJECT", "MOVE_TO_INTERVIEW", "SELECT");

    private final ApplicationAccess access;
    private final ApplicationDecisionService decisionService;

    public DecideApplicationTool(ApplicationAccess access, ApplicationDecisionService decisionService) {
        this.access = access;
        this.decisionService = decisionService;
    }

    @Override
    public String name() {
        return "decide_application";
    }

    @Override
    public String description() {
        return "Record a decision on an application: SHORTLIST, REJECT (reason required), MOVE_TO_INTERVIEW or SELECT.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.required("applicationCode", ArgType.APPLICATION_CODE),
                ArgSpec.required("decision", ArgType.ENUM).oneOf(DECISIONS),
                ArgSpec.optional("reason", ArgType.STRING).maxLength(2000).hint("required for REJECT"));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        ApplicationDecisionAction action = ApplicationDecisionAction.valueOf(args.getString("decision"));
        requireReason(action, args);
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        ApplicationStatus target = ApplicationTransitions.target(info.status(), action);
        if (target == null) {
            throw new AssistantToolException(info.code() + " is " + info.status() + ", so it cannot be "
                    + describe(action) + "; allowed now: " + ApplicationTransitions.allowedDecisions(info.status()));
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("applicationId", info.applicationId());
        preview.put("applicationCode", info.code());
        preview.put("revealedName", info.revealedName());
        preview.put("jobId", info.jobId());
        preview.put("jobTitle", info.jobTitle());
        preview.put("decision", action.name());
        preview.put("from", info.status().name());
        preview.put("to", target.name());
        preview.put("reason", args.getString("reason"));
        preview.put("notes", action == ApplicationDecisionAction.SHORTLIST && !info.revealed()
                ? "Shortlisting reveals the candidate's identity to reviewers (recorded in the history and audit log). "
                        + "The candidate is notified of every status change."
                : "The candidate is notified of the status change.");
        preview.put("link", Tools.applicantLink(info.jobId(), info.applicationId()));
        String summary = capitalise(describe(action)) + " " + info.code() + " for \"" + info.jobTitle() + "\" ("
                + info.status() + " -> " + target + ")";
        return new ActionProposal(summary, new LinkedHashMap<>(args.values()), preview, List.of());
    }

    /** The application must still be in the status the user saw when the decision was proposed. */
    @Override
    public void checkUnchanged(ToolContext context, ToolArgs args, Map<String, Object> preview) {
        Object from = preview == null ? null : preview.get("from");
        if (from == null) {
            return;
        }
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        if (info.status() == null || !info.status().name().equals(String.valueOf(from))) {
            throw new AssistantToolException(info.code() + " changed from " + from + " to " + info.status()
                    + " since this was proposed");
        }
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        ApplicationDecisionAction action = ApplicationDecisionAction.valueOf(args.getString("decision"));
        requireReason(action, args);
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        decisionService.decide(info.jobId(), info.applicationId(), new DecisionRequest(action, args.getString("reason")));
        ApplicationStatus target = ApplicationTransitions.target(info.status(), action);
        return new ActionOutcome("application", info.applicationId(), Tools.applicantLink(info.jobId(), info.applicationId()),
                info.code() + " is now " + (target == null ? "updated" : target.name()) + ".", "APPLICATION",
                String.valueOf(info.applicationId()), "Application #" + info.applicationId() + " decision " + action);
    }

    private static void requireReason(ApplicationDecisionAction action, ToolArgs args) {
        if (action == ApplicationDecisionAction.REJECT && (args.getString("reason") == null || args.getString("reason").isBlank())) {
            throw new AssistantToolException("A reason is required to reject an application");
        }
    }

    static String describe(ApplicationDecisionAction action) {
        return switch (action) {
            case SHORTLIST -> "shortlisted";
            case REJECT -> "rejected";
            case MOVE_TO_INTERVIEW -> "moved to interview";
            case SELECT -> "selected";
            default -> action.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String capitalise(String text) {
        String verb = switch (text) {
            case "shortlisted" -> "shortlist";
            case "rejected" -> "reject";
            case "moved to interview" -> "move to interview";
            case "selected" -> "select";
            default -> text;
        };
        return verb.substring(0, 1).toUpperCase(Locale.ROOT) + verb.substring(1);
    }
}
