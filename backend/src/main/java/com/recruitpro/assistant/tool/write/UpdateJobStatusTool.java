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
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.hr.service.JobService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** update_job_status: publish, put on hold, close or cancel a job (JobService.transition on confirm, audited there too). */
@Component
public class UpdateJobStatusTool implements WriteTool {

    private final JobService jobService;
    private final HrJobAccessService jobAccess;

    public UpdateJobStatusTool(JobService jobService, HrJobAccessService jobAccess) {
        this.jobService = jobService;
        this.jobAccess = jobAccess;
    }

    @Override
    public String name() {
        return "update_job_status";
    }

    @Override
    public String description() {
        return "Change a job's status: PUBLISHED (publish or resume), ON_HOLD, CLOSED or CANCELLED (drafts only).";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.required("jobId", ArgType.INTEGER).range(1, Long.MAX_VALUE),
                ArgSpec.required("status", ArgType.ENUM).oneOf(List.of("PUBLISHED", "ON_HOLD", "CLOSED", "CANCELLED")));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    /** Job.transitionTo's rules, checked before proposing (the service checks again on confirm). */
    static boolean allowed(JobStatus from, JobStatus to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case DRAFT -> to == JobStatus.PUBLISHED || to == JobStatus.CANCELLED;
            case PUBLISHED -> to == JobStatus.ON_HOLD || to == JobStatus.CLOSED;
            case ON_HOLD -> to == JobStatus.PUBLISHED || to == JobStatus.CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        Job job = jobAccess.requireManageAccess(args.getLong("jobId"));
        JobStatus target = JobStatus.valueOf(args.getString("status"));
        if (!allowed(job.getStatus(), target)) {
            throw new AssistantToolException("Job #" + job.getId() + " is " + job.getStatus() + " and cannot change to "
                    + target);
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("jobId", job.getId());
        preview.put("jobTitle", job.getTitle());
        preview.put("from", job.getStatus().name());
        preview.put("to", target.name());
        preview.put("link", Tools.jobLink(job.getId()));
        String verb = switch (target) {
            case PUBLISHED -> job.getStatus() == JobStatus.ON_HOLD ? "Resume" : "Publish";
            case ON_HOLD -> "Put on hold";
            case CLOSED -> "Close";
            case CANCELLED -> "Cancel";
            case DRAFT -> "Move to draft";
        };
        return new ActionProposal(verb + " the job \"" + job.getTitle() + "\"", new LinkedHashMap<>(args.values()), preview,
                List.of());
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        JobStatus target = JobStatus.valueOf(args.getString("status"));
        JobStaffResponse job = jobService.transition(args.getLong("jobId"), target);
        return new ActionOutcome("job", job.id(), Tools.jobLink(job.id()),
                "The job \"" + job.title() + "\" is now " + job.status() + ".", "JOB", String.valueOf(job.id()),
                "Job #" + job.id() + " status -> " + target);
    }
}
