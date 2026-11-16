package com.recruitpro.assistant.tool.read;

import com.recruitpro.assistant.tool.ArgSpec;
import com.recruitpro.assistant.tool.ArgType;
import com.recruitpro.assistant.tool.ReadTool;
import com.recruitpro.assistant.tool.ToolArgs;
import com.recruitpro.assistant.tool.ToolCapability;
import com.recruitpro.assistant.tool.ToolContext;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.fairness.dto.FairnessReport;
import com.recruitpro.fairness.service.FairnessReportService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fairness_summary: the adverse-impact summary of a job (ADMIN, HR_MANAGER) or, without jobId, of the organisation
 * (ADMIN only, as on the admin page). Aggregates only; small groups are already suppressed by the report.
 */
@Component
public class FairnessSummaryTool implements ReadTool {

    private final FairnessReportService fairnessReportService;

    public FairnessSummaryTool(FairnessReportService fairnessReportService) {
        this.fairnessReportService = fairnessReportService;
    }

    @Override
    public String name() {
        return "fairness_summary";
    }

    @Override
    public String description() {
        return "Fairness (adverse impact) summary of a job, or of the whole organisation when jobId is omitted "
                + "(administrators): flagged stages, impact ratios, survey response rate.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(ArgSpec.optional("jobId", ArgType.INTEGER).range(1, Long.MAX_VALUE));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public Map<String, Object> run(ToolContext context, ToolArgs args) {
        Long jobId = args.getLong("jobId");
        FairnessReport report = jobId == null ? fairnessReportService.forOrganization() : fairnessReportService.forJob(jobId);
        List<Map<String, Object>> flagged = new ArrayList<>();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (FairnessReport.DimensionReport dimension : report.dimensions()) {
            for (FairnessReport.StageReport stage : dimension.stages()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("dimension", dimension.dimension());
                item.put("stage", stage.stage());
                item.put("adverseImpactRatio", stage.adverseImpactRatio());
                item.put("flagged", stage.flagged());
                if (stage.flagged()) {
                    flagged.add(item);
                }
                if (stages.size() < 40) {
                    stages.add(item);
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", report.scope());
        data.put("jobId", report.jobId());
        data.put("minimumGroupSize", report.minimumGroupSize());
        data.put("surveyResponseRate", report.surveyResponseRate());
        data.put("flaggedStages", flagged);
        data.put("stages", stages);
        data.put("note", report.note());
        data.put("link", jobId == null ? "/admin/fairness" : Tools.jobTabLink(jobId, "fairness"));
        return data;
    }
}
