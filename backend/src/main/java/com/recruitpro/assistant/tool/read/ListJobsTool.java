package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.service.JobService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** list_jobs: the organisation's jobs (newest first, at most 20), optionally by status or title. */
@Component
public class ListJobsTool implements ReadTool {

    static final int LIMIT = 20;

    private final JobService jobService;

    public ListJobsTool(JobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public String name() {
        return "list_jobs";
    }

    @Override
    public String description() {
        return "List the organisation's jobs (newest first, at most 20) with status, location and applicant counts. "
                + "Use it to find a job id by title.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.optional("status", ArgType.ENUM).oneOf(Tools.names(JobStatus.values())),
                ArgSpec.optional("search", ArgType.STRING).maxLength(100).hint("part of the title"));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_READ;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        JobStatus status = args.has("status") ? JobStatus.valueOf(args.getString("status")) : null;
        PageResponse<JobStaffResponse> page = jobService.listForStaff(0, LIMIT, status, null, null, null,
                args.getString("search"), "createdAt", Sort.Direction.DESC);
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobStaffResponse job : page.getContent()) {
            jobs.add(Tools.jobSummary(job));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", page.getTotalElements());
        data.put("jobs", jobs);
        data.put("link", "/jobs");
        return data;
    }
}
