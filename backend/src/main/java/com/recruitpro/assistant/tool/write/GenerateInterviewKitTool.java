package com.recruitpro.assistant.tool.write;

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
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.interview.dto.InterviewKitResponse;
import com.recruitpro.interview.service.InterviewKitService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * generate_interview_kit: drafts structured interview questions per requirement and replaces the job's kit
 * (InterviewKitService.generate on confirm). Not transactional (AI call).
 */
@Component
public class GenerateInterviewKitTool implements WriteTool {

    private final InterviewKitService interviewKitService;
    private final HrJobAccessService jobAccess;

    public GenerateInterviewKitTool(InterviewKitService interviewKitService, HrJobAccessService jobAccess) {
        this.interviewKitService = interviewKitService;
        this.jobAccess = jobAccess;
    }

    @Override
    public String name() {
        return "generate_interview_kit";
    }

    @Override
    public String description() {
        return "Draft an interview kit (structured questions with anchored ratings per requirement) for a job; replaces "
                + "the job's current kit.";
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
            throw new AssistantToolException("Cancelled jobs cannot get an interview kit");
        }
        Integer existing = null;
        try {
            existing = interviewKitService.get(job.getId()).questions().size();
        } catch (ResourceNotFoundException noKitYet) {
            // nothing to replace
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("jobId", job.getId());
        preview.put("jobTitle", job.getTitle());
        preview.put("existingQuestions", existing);
        preview.put("notes", existing == null
                ? "Drafts one or two questions per requirement with anchored 1-4 ratings."
                : "Replaces the current kit (" + existing + " questions) with a new draft.");
        preview.put("link", Tools.jobTabLink(job.getId(), "interview-kit"));
        return new ActionProposal("Draft an interview kit for \"" + job.getTitle() + "\"", new LinkedHashMap<>(args.values()),
                preview, List.of());
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        InterviewKitResponse kit = interviewKitService.generate(args.getLong("jobId"));
        return new ActionOutcome("interview_kit", kit.jobId(), Tools.jobTabLink(kit.jobId(), "interview-kit"),
                "Drafted an interview kit with " + kit.questions().size() + " questions.", "JOB",
                String.valueOf(kit.jobId()), "Interview kit drafted for job #" + kit.jobId() + " (" + kit.questions().size()
                        + " questions)");
    }
}
