package com.recruitpro.assistant.tool;

import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.ApplicantSummaryResponse;
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.service.ApplicantQueryService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers of the tools: portal links, de-identified summaries, rounding. */
public final class Tools {

    /** Applicants read per job by the insight tools (5 pages of 100). */
    public static final int MAX_APPLICANTS = 500;

    private Tools() {
    }

    public static List<String> names(Enum<?>[] constants) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : constants) {
            names.add(constant.name());
        }
        return List.copyOf(names);
    }

    public static String jobLink(Long jobId) {
        return "/jobs/" + jobId;
    }

    public static String jobTabLink(Long jobId, String tab) {
        return "/jobs/" + jobId + "?tab=" + tab;
    }

    public static String applicantLink(Long jobId, Long applicationId) {
        return "/jobs/" + jobId + "/applicants/" + applicationId;
    }

    public static String interviewLink(Long interviewId) {
        return "/interviews/" + interviewId;
    }

    /** ISO-8601 UTC text of a time (null stays null). */
    public static String time(LocalDateTime value) {
        return UtcTime.format(value);
    }

    public static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    /** A job as the planner sees it (HR-authored fields only). */
    public static Map<String, Object> jobSummary(JobStaffResponse job) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", job.id());
        item.put("title", job.title());
        item.put("status", job.status());
        item.put("department", job.department());
        item.put("location", job.location());
        item.put("applicationCount", job.applicationCount());
        item.put("deadline", time(job.deadline()));
        item.put("link", jobLink(job.id()));
        return item;
    }

    /** Every applicant of the job in rank order (at most {@link #MAX_APPLICANTS}), as the user may see them. */
    public static List<ApplicantSummaryResponse> rankedApplicants(ApplicantQueryService queryService, Long jobId) {
        List<ApplicantSummaryResponse> all = new ArrayList<>();
        int page = 0;
        while (all.size() < MAX_APPLICANTS) {
            PageResponse<ApplicantSummaryResponse> slice = queryService.list(jobId, page, 100, null, ApplicantQueryService.SORT_RANK);
            all.addAll(slice.getContent());
            if (slice.isLast() || slice.getContent().isEmpty()) {
                break;
            }
            page++;
        }
        return all;
    }

    /** An applicant by code (never the display name: it is the real name once revealed). */
    public static Map<String, Object> candidate(ApplicantSummaryResponse applicant, Long jobId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("applicationId", applicant.applicationId());
        item.put("code", ApplicationCodes.code(applicant.applicationId()));
        item.put("status", applicant.applicationStatus());
        item.put("group", applicant.rankGroup());
        item.put("rank", applicant.rank());
        item.put("verified", round1(applicant.verifiedCoverage()));
        item.put("claimed", round1(applicant.claimedCoverage()));
        item.put("knockoutsVerified", applicant.knockoutsVerified());
        item.put("matchStatus", applicant.matchStatus());
        item.put("link", applicantLink(jobId, applicant.applicationId()));
        return item;
    }
}
