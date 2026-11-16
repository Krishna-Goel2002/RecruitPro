package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.hr.dto.ApplicantDetailResponse;
import com.recruitpro.hr.dto.RequirementEvidenceResponse;
import com.recruitpro.hr.service.ApplicantQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * compare_applicants: two to four applicants of the same job side by side, requirement by requirement (status and
 * tier), plus coverage, ranking group and status. Reads ApplicantQueryService.detail as the user.
 */
@Component
public class CompareApplicantsTool implements ReadTool {

    static final int MAX = 4;

    private final ApplicationAccess access;
    private final ApplicantQueryService queryService;

    public CompareApplicantsTool(ApplicationAccess access, ApplicantQueryService queryService) {
        this.access = access;
        this.queryService = queryService;
    }

    @Override
    public String name() {
        return "compare_applicants";
    }

    @Override
    public String description() {
        return "Compare 2 to 4 applicants of the same job requirement by requirement (status and evidence tier), "
                + "with verified and claimed coverage, ranking group and status.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.required("applicationCodes", ArgType.APPLICATION_CODES).maxLength(MAX));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_READ;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        List<Long> ids = args.getApplicationIds("applicationCodes");
        if (ids.size() < 2) {
            throw new AssistantToolException("Name at least two application codes to compare");
        }
        List<AppInfo> infos = new ArrayList<>();
        for (Long id : ids) {
            infos.add(access.requireReadable(context, id));
        }
        Long jobId = infos.get(0).jobId();
        for (AppInfo info : infos) {
            if (!Objects.equals(info.jobId(), jobId)) {
                throw new AssistantToolException("Only applicants of the same job can be compared; " + info.code()
                        + " applied to another job");
            }
        }
        List<Map<String, Object>> applicants = new ArrayList<>();
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        for (AppInfo info : infos) {
            ApplicantDetailResponse detail = queryService.detail(jobId, info.applicationId());
            Map<String, Object> applicant = new LinkedHashMap<>();
            applicant.put("applicationId", info.applicationId());
            applicant.put("code", info.code());
            applicant.put("status", detail.applicationStatus());
            applicant.put("group", detail.rankGroup());
            applicant.put("rank", detail.rank());
            applicant.put("verified", Tools.round1(detail.verifiedCoverage()));
            applicant.put("claimed", Tools.round1(detail.claimedCoverage()));
            applicant.put("knockoutsVerified", detail.knockoutsVerified());
            applicant.put("workSampleStatus", detail.workSampleStatus());
            applicant.put("interviewCount", detail.interviewCount());
            applicant.put("link", Tools.applicantLink(jobId, info.applicationId()));
            applicants.add(applicant);
            for (RequirementEvidenceResponse requirement : detail.requirements()) {
                Map<String, Object> row = rows.computeIfAbsent(requirement.requirementId(), key -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("requirementId", requirement.requirementId());
                    created.put("requirement", requirement.description());
                    created.put("knockout", requirement.knockout());
                    created.put("mandatory", requirement.mandatory());
                    created.put("results", new ArrayList<Map<String, Object>>());
                    return created;
                });
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("code", info.code());
                result.put("status", requirement.status());
                result.put("tier", requirement.tier());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) row.get("results");
                results.add(result);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("jobTitle", infos.get(0).jobTitle());
        data.put("applicants", applicants);
        data.put("requirements", new ArrayList<>(rows.values()));
        data.put("link", Tools.jobLink(jobId));
        return data;
    }
}
