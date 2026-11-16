package com.recruitpro.assistant.tool.read;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assistant.tool.ApplicationCodes;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.ApplicantSummaryResponse;
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.JobService;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.service.InterviewService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * attention_digest: "what needs my attention today?" Over the newest published and on-hold jobs: applicants waiting
 * for a first decision (and how many arrived in the last 7 days), submitted work samples awaiting a score, matching
 * still pending and deadlines in the next 7 days; plus the user's own interviews that are past without a scorecard
 * and those in the next 7 days. Shared verdicts awaiting review are not included (no per-organisation queue exists
 * outside the admin verification page).
 */
@Component
public class AttentionDigestTool implements ReadTool {

    static final int MAX_JOBS = 10;

    private final JobService jobService;
    private final ApplicantQueryService queryService;
    private final InterviewService interviewService;

    public AttentionDigestTool(JobService jobService, ApplicantQueryService queryService, InterviewService interviewService) {
        this.jobService = jobService;
        this.queryService = queryService;
        this.interviewService = interviewService;
    }

    @Override
    public String name() {
        return "attention_digest";
    }

    @Override
    public String description() {
        return "What needs attention today: new applicants awaiting a first decision, work samples awaiting a score, "
                + "pending matching, application deadlines in the next 7 days, your interviews without a scorecard and "
                + "your interviews in the next 7 days.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of();
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_READ;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        LocalDateTime now = UtcTime.now();
        List<JobStaffResponse> jobs = new ArrayList<>();
        for (JobStatus status : List.of(JobStatus.PUBLISHED, JobStatus.ON_HOLD)) {
            PageResponse<JobStaffResponse> page = jobService.listForStaff(0, MAX_JOBS, status, null, null, null, null,
                    "createdAt", Sort.Direction.DESC);
            jobs.addAll(page.getContent());
        }
        if (jobs.size() > MAX_JOBS) {
            jobs = jobs.subList(0, MAX_JOBS);
        }
        List<Map<String, Object>> awaitingDecision = new ArrayList<>();
        List<Map<String, Object>> unscoredWorkSamples = new ArrayList<>();
        List<Map<String, Object>> pendingMatching = new ArrayList<>();
        List<Map<String, Object>> deadlines = new ArrayList<>();
        for (JobStaffResponse job : jobs) {
            if (job.deadline() != null && job.deadline().isAfter(now) && job.deadline().isBefore(now.plusDays(7))) {
                deadlines.add(jobItem(job, "deadline", Tools.time(job.deadline())));
            }
            if (job.applicationCount() == 0) {
                continue;
            }
            long submitted = 0;
            long recent = 0;
            long workSamples = 0;
            long pending = 0;
            for (ApplicantSummaryResponse applicant : Tools.rankedApplicants(queryService, job.id())) {
                if (applicant.applicationStatus() == ApplicationStatus.SUBMITTED) {
                    submitted++;
                    if (applicant.appliedAt() != null && applicant.appliedAt().isAfter(now.minusDays(7))) {
                        recent++;
                    }
                }
                if ("SUBMITTED".equals(applicant.workSampleStatus())) {
                    workSamples++;
                }
                if ("PENDING".equals(applicant.matchStatus())) {
                    pending++;
                }
            }
            if (submitted > 0) {
                Map<String, Object> item = jobItem(job, "count", submitted);
                item.put("newLast7Days", recent);
                awaitingDecision.add(item);
            }
            if (workSamples > 0) {
                unscoredWorkSamples.add(jobItem(job, "count", workSamples));
            }
            if (pending > 0) {
                pendingMatching.add(jobItem(job, "count", pending));
            }
        }

        List<Map<String, Object>> withoutScorecard = new ArrayList<>();
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (InterviewResponse interview : interviewService.mine()) {
            if (interview.status() != InterviewStatus.SCHEDULED || interview.scheduledAt() == null) {
                continue;
            }
            if (interview.scheduledAt().isBefore(now) && interview.scorecard() == null && withoutScorecard.size() < 10) {
                withoutScorecard.add(interviewItem(interview));
            } else if (interview.scheduledAt().isAfter(now) && interview.scheduledAt().isBefore(now.plusDays(7))
                    && upcoming.size() < 10) {
                upcoming.add(interviewItem(interview));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobsChecked", jobs.size());
        data.put("awaitingFirstDecision", awaitingDecision);
        data.put("workSamplesAwaitingScore", unscoredWorkSamples);
        data.put("pendingMatching", pendingMatching);
        data.put("deadlinesNext7Days", deadlines);
        data.put("myInterviewsWithoutScorecard", withoutScorecard);
        data.put("myInterviewsNext7Days", upcoming);
        return data;
    }

    private static Map<String, Object> jobItem(JobStaffResponse job, String key, Object value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("jobId", job.id());
        item.put("jobTitle", job.title());
        item.put(key, value);
        item.put("link", Tools.jobLink(job.id()));
        return item;
    }

    static Map<String, Object> interviewItem(InterviewResponse interview) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("interviewId", interview.id());
        item.put("applicationId", interview.applicationId());
        item.put("applicationCode", ApplicationCodes.code(interview.applicationId()));
        item.put("jobId", interview.jobId());
        item.put("jobTitle", interview.jobTitle());
        item.put("scheduledAt", Tools.time(interview.scheduledAt()));
        item.put("durationMinutes", interview.durationMinutes());
        item.put("status", interview.status());
        item.put("scorecardSubmitted", interview.scorecard() != null);
        item.put("applicationStatus", interview.applicationStatus());
        item.put("link", Tools.interviewLink(interview.id()));
        return item;
    }
}
