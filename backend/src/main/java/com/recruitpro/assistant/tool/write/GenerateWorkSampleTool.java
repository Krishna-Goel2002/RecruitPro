package com.recruitpro.assistant.tool.write;

import com.recruitpro.assessment.dto.WorkSampleResponse;
import com.recruitpro.assessment.service.WorkSampleService;
import com.recruitpro.assistant.tool.ActionOutcome;
import com.recruitpro.assistant.tool.ActionProposal;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.assistant.tool.WriteTool;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.service.HrJobAccessService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * generate_work_sample: drafts a work sample with an anchored rubric (WorkSampleService.generate on confirm, stored as
 * DRAFT). Not transactional: the service must not hold a database transaction during the AI call.
 */
@Component
public class GenerateWorkSampleTool implements WriteTool {

    private final WorkSampleService workSampleService;
    private final HrJobAccessService jobAccess;

    public GenerateWorkSampleTool(WorkSampleService workSampleService, HrJobAccessService jobAccess) {
        this.workSampleService = workSampleService;
        this.jobAccess = jobAccess;
    }

    @Override
    public String name() {
        return "generate_work_sample";
    }

    @Override
    public String description() {
        return "Draft a work sample (task with an anchored 1-4 rubric) for a job; saved as a draft to review and activate.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.required("jobId", ArgType.INTEGER).range(1, Long.MAX_VALUE));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        Job job = jobAccess.requireManageAccess(args.getLong("jobId"));
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new AssistantToolException("Cancelled jobs cannot get work samples");
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("jobId", job.getId());
        preview.put("jobTitle", job.getTitle());
        preview.put("notes", "Drafts a task and rubric from the job's requirements and saves it as a DRAFT; nothing is "
                + "sent to candidates until you activate and assign it.");
        preview.put("link", Tools.jobTabLink(job.getId(), "work-samples"));
        return new ActionProposal("Draft a work sample for \"" + job.getTitle() + "\"", new LinkedHashMap<>(args.values()),
                preview, List.of());
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        WorkSampleResponse sample = workSampleService.generate(args.getLong("jobId"));
        return new ActionOutcome("work_sample", sample.id(), Tools.jobTabLink(sample.jobId(), "work-samples"),
                "Drafted the work sample \"" + sample.title() + "\". Review it and activate it before assigning.",
                "WORK_SAMPLE", String.valueOf(sample.id()), "Work sample #" + sample.id() + " drafted for job #" + sample.jobId());
    }
}
