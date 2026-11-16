package com.recruitpro.assistant.tool.write;

import com.recruitpro.assessment.dto.AssignWorkSampleRequest;
import com.recruitpro.assessment.dto.WorkSampleAssignmentResponse;
import com.recruitpro.assessment.dto.WorkSampleResponse;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.service.WorkSampleService;
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
import com.recruitpro.common.time.UtcTime;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * assign_work_sample: assigns an ACTIVE work sample of the job to a shortlisted or interviewing application
 * (WorkSampleService.assign on confirm; notifies the candidate). Without workSampleId the job's only active sample is
 * used; with several, the error lists them so the planner can ask.
 */
@Component
public class AssignWorkSampleTool implements WriteTool {

    private final ApplicationAccess access;
    private final WorkSampleService workSampleService;

    public AssignWorkSampleTool(ApplicationAccess access, WorkSampleService workSampleService) {
        this.access = access;
        this.workSampleService = workSampleService;
    }

    @Override
    public String name() {
        return "assign_work_sample";
    }

    @Override
    public String description() {
        return "Assign an active work sample of the job to a shortlisted or interviewing applicant (the job's only "
                + "active sample when workSampleId is omitted).";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.required("applicationCode", ArgType.APPLICATION_CODE),
                ArgSpec.optional("workSampleId", ArgType.INTEGER).range(1, Long.MAX_VALUE),
                ArgSpec.optional("dueAt", ArgType.DATE_TIME).hint("latest start time, in the future"));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        if (!WorkSampleService.ASSIGNABLE.contains(info.status())) {
            throw new AssistantToolException(info.code() + " is " + info.status()
                    + "; work samples can be assigned to shortlisted or interviewing applications only");
        }
        LocalDateTime dueAt = args.getDateTime("dueAt");
        if (dueAt != null && !dueAt.isAfter(UtcTime.now())) {
            throw new AssistantToolException("The due date must be in the future");
        }
        List<WorkSampleResponse> active = workSampleService.list(info.jobId()).stream()
                .filter(sample -> sample.status() == WorkSampleStatus.ACTIVE).toList();
        WorkSampleResponse sample;
        if (args.has("workSampleId")) {
            sample = active.stream().filter(item -> Objects.equals(item.id(), args.getLong("workSampleId"))).findFirst()
                    .orElseThrow(() -> new AssistantToolException("Work sample #" + args.getLong("workSampleId")
                            + " is not an active work sample of this job" + options(active)));
        } else if (active.size() == 1) {
            sample = active.get(0);
        } else if (active.isEmpty()) {
            throw new AssistantToolException("The job \"" + info.jobTitle() + "\" has no active work sample; generate "
                    + "or activate one first");
        } else {
            throw new AssistantToolException("The job has several active work samples; choose one" + options(active));
        }
        Map<String, Object> normalised = new LinkedHashMap<>(args.values());
        normalised.put("workSampleId", sample.id());
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("applicationId", info.applicationId());
        preview.put("applicationCode", info.code());
        preview.put("revealedName", info.revealedName());
        preview.put("jobId", info.jobId());
        preview.put("jobTitle", info.jobTitle());
        preview.put("workSampleId", sample.id());
        preview.put("workSampleTitle", sample.title());
        preview.put("timeLimitMinutes", sample.timeLimitMinutes());
        preview.put("dueAt", Tools.time(dueAt));
        preview.put("notes", "The candidate is notified and starts the timed task from their portal.");
        preview.put("link", Tools.applicantLink(info.jobId(), info.applicationId()) + "?tab=work-samples");
        return new ActionProposal("Assign \"" + sample.title() + "\" to " + info.code(), normalised, preview, List.of());
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        AppInfo info = access.requireManageable(context, args.getApplicationId("applicationCode"));
        Long workSampleId = args.getLong("workSampleId");
        if (workSampleId == null) {
            throw new AssistantToolException("workSampleId is required");
        }
        WorkSampleAssignmentResponse assignment = workSampleService.assign(info.jobId(), info.applicationId(),
                new AssignWorkSampleRequest(workSampleId, args.getDateTime("dueAt")));
        return new ActionOutcome("work_sample_assignment", assignment.id(),
                Tools.applicantLink(info.jobId(), info.applicationId()) + "?tab=work-samples",
                "Assigned \"" + assignment.workSampleTitle() + "\" to " + info.code() + ".", "WORK_SAMPLE_ASSIGNMENT",
                String.valueOf(assignment.id()), "Work sample #" + workSampleId + " assigned to application #"
                        + info.applicationId());
    }

    private static String options(List<WorkSampleResponse> active) {
        if (active.isEmpty()) return "";
        StringBuilder text = new StringBuilder(" (active: ");
        for (int i = 0; i < active.size() && i < 10; i++) {
            if (i > 0) text.append(", ");
            text.append('#').append(active.get(i).id()).append(" \"").append(active.get(i).title()).append('"');
        }
        return text.append(')').toString();
    }
}
