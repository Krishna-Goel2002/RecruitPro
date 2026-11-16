package com.recruitpro.assistant.tool.read;

import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.ApplicantSummaryResponse;
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.dto.RequirementResponse;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.JobService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * job_insights: how a posting is doing. Pipeline counts per status, ranking groups, the top applicants by verified
 * coverage (codes), the must-haves most often unverified, match and work-sample backlog and the average days spent in
 * the current stage. Reads through JobService and ApplicantQueryService as the user.
 */
@Component
public class JobInsightsTool implements ReadTool {

    static final int TOP = 5;

    private final JobService jobService;
    private final ApplicantQueryService queryService;

    public JobInsightsTool(JobService jobService, ApplicantQueryService queryService) {
        this.jobService = jobService;
        this.queryService = queryService;
    }

    @Override
    public String name() {
        return "job_insights";
    }

    @Override
    public String description() {
        return "How a job posting is doing: pipeline counts per status, ranking groups, top applicants by verified "
                + "coverage (application codes), must-have requirements most often unverified, pending matching, "
                + "work samples awaiting scoring, average days in the current stage.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.required("jobId", ArgType.INTEGER).range(1, Long.MAX_VALUE));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_READ;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        JobStaffResponse job = jobService.getForStaff(args.getLong("jobId"));
        List<ApplicantSummaryResponse> applicants = Tools.rankedApplicants(queryService, job.id());
        LocalDateTime now = UtcTime.now();

        Map<String, Long> pipeline = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            pipeline.put(status.name(), 0L);
        }
        Map<String, Long> groups = new LinkedHashMap<>();
        groups.put("RANKED", 0L);
        groups.put("MUST_HAVES_UNVERIFIED", 0L);
        groups.put("UNSCORED", 0L);
        Map<Long, Integer> unverifiedKnockouts = new HashMap<>();
        Map<String, double[]> stageDays = new LinkedHashMap<>();
        long pendingMatch = 0;
        long awaitingScore = 0;
        long newThisWeek = 0;
        List<Map<String, Object>> top = new ArrayList<>();
        for (ApplicantSummaryResponse applicant : applicants) {
            String status = applicant.applicationStatus() == null ? "UNKNOWN" : applicant.applicationStatus().name();
            pipeline.merge(status, 1L, Long::sum);
            if (applicant.rankGroup() != null) {
                groups.merge(applicant.rankGroup(), 1L, Long::sum);
            }
            for (Long requirementId : applicant.unverifiedKnockouts() == null ? List.<Long>of() : applicant.unverifiedKnockouts()) {
                unverifiedKnockouts.merge(requirementId, 1, Integer::sum);
            }
            if ("PENDING".equals(applicant.matchStatus())) {
                pendingMatch++;
            }
            if ("SUBMITTED".equals(applicant.workSampleStatus())) {
                awaitingScore++;
            }
            if (applicant.appliedAt() != null && applicant.appliedAt().isAfter(now.minusDays(7))) {
                newThisWeek++;
            }
            LocalDateTime since = applicant.statusChangedAt() != null ? applicant.statusChangedAt() : applicant.appliedAt();
            if (since != null) {
                double[] sum = stageDays.computeIfAbsent(status, key -> new double[2]);
                sum[0] += Duration.between(since, now).toHours() / 24.0;
                sum[1] += 1;
            }
            if ("RANKED".equals(applicant.rankGroup()) && top.size() < TOP) {
                top.add(Tools.candidate(applicant, job.id()));
            }
        }

        Map<Long, RequirementResponse> requirements = new HashMap<>();
        for (RequirementResponse requirement : job.requirements()) {
            requirements.put(requirement.id(), requirement);
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        unverifiedKnockouts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed().thenComparing(Map.Entry.<Long, Integer>comparingByKey()))
                .limit(TOP)
                .forEach(entry -> {
                    RequirementResponse requirement = requirements.get(entry.getKey());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("requirementId", entry.getKey());
                    item.put("requirement", requirement == null ? "Requirement #" + entry.getKey() : requirement.description());
                    item.put("applicantsWithoutVerifiedEvidence", entry.getValue());
                    missing.add(item);
                });
        Map<String, Object> averageDays = new LinkedHashMap<>();
        stageDays.forEach((status, sum) -> averageDays.put(status, Tools.round1(sum[0] / sum[1])));

        Map<String, Object> jobData = Tools.jobSummary(job);
        jobData.put("publishedAt", Tools.time(job.publishedAt()));
        jobData.put("requirementCount", job.requirements().size());
        jobData.put("knockoutCount", job.requirements().stream().filter(RequirementResponse::knockout).count());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job", jobData);
        data.put("applicants", applicants.size());
        data.put("newLast7Days", newThisWeek);
        data.put("pipeline", pipeline);
        data.put("rankingGroups", groups);
        data.put("topApplicants", top);
        data.put("mostUnverifiedMustHaves", missing);
        data.put("pendingMatching", pendingMatch);
        data.put("workSamplesAwaitingScore", awaitingScore);
        data.put("averageDaysInCurrentStage", averageDays);
        data.put("link", Tools.jobLink(job.id()));
        return data;
    }
}
