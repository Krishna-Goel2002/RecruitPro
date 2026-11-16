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
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.dto.CompileRequirementsRequest;
import com.recruitpro.hr.dto.CompileRequirementsResponse;
import com.recruitpro.hr.dto.JobCreateRequest;
import com.recruitpro.hr.dto.JobStaffResponse;
import com.recruitpro.hr.dto.RequirementInput;
import com.recruitpro.hr.dto.RequirementsReplaceRequest;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.hr.service.JobService;
import com.recruitpro.hr.service.RequirementCompilerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * create_job: the job-posting draft ({@code draft.fields}, ASSISTANT_PLAN 3.2) as a proposed action. The preview runs
 * the existing requirement compiler for wording warnings (and suggested requirements when the draft has none). On
 * confirm, in one transaction: JobService.create (DRAFT), JobService.replaceRequirements (requirements v2) and, for
 * mode PUBLISH, JobService.transition(PUBLISHED). The mode is the {@code publish} argument, which the confirm request
 * may override ({"mode": "DRAFT"|"PUBLISH"}: the UI's "Save as draft" / "Publish" buttons).
 */
@Component
public class CreateJobTool implements WriteTool {

    public static final String NAME = "create_job";
    static final String PUBLISH = "publish";

    private static final Logger log = LoggerFactory.getLogger(CreateJobTool.class);

    private final JobService jobService;
    private final RequirementCompilerService compilerService;
    private final HrJobAccessService jobAccess;

    public CreateJobTool(JobService jobService, RequirementCompilerService compilerService, HrJobAccessService jobAccess) {
        this.jobService = jobService;
        this.compilerService = compilerService;
        this.jobAccess = jobAccess;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Create a job posting from the draft fields (shown as a preview with wording warnings; the user chooses "
                + "Save as draft or Publish). Ask for missing required fields first.";
    }

    @Override
    public List<ArgSpec> args() {
        return List.of(
                ArgSpec.required("title", ArgType.STRING).maxLength(255),
                ArgSpec.required("department", ArgType.STRING).maxLength(100),
                ArgSpec.required("location", ArgType.STRING).maxLength(255),
                ArgSpec.required("jobType", ArgType.ENUM).oneOf(Tools.names(JobType.values())),
                ArgSpec.required("experienceLevel", ArgType.ENUM).oneOf(Tools.names(ExperienceLevel.values())),
                ArgSpec.optional("salaryMin", ArgType.NUMBER).range(0, 9_999_999_999L),
                ArgSpec.optional("salaryMax", ArgType.NUMBER).range(0, 9_999_999_999L),
                ArgSpec.optional("openings", ArgType.INTEGER).range(1, 10_000).hint("default 1"),
                ArgSpec.optional("deadline", ArgType.DATE_TIME).hint("application deadline in the future"),
                ArgSpec.optional("summary", ArgType.STRING).maxLength(10_000),
                ArgSpec.optional("responsibilities", ArgType.STRING_LIST).maxLength(30),
                ArgSpec.optional("description", ArgType.STRING).maxLength(50_000)
                        .hint("full description; built from summary and responsibilities when omitted"),
                ArgSpec.optional("requirements", ArgType.REQUIREMENTS),
                ArgSpec.optional(PUBLISH, ArgType.BOOLEAN).hint("default false (save as draft)"));
    }

    @Override
    public ToolCapability capability() {
        return ToolCapability.JOBS_MANAGE;
    }

    @Override
    public ActionProposal prepare(ToolContext context, ToolArgs args) {
        jobAccess.requireJobManager();
        String description = description(args);
        validate(args);
        ExperienceLevel level = ExperienceLevel.valueOf(args.getString("experienceLevel"));

        List<Map<String, Object>> requirements = new ArrayList<>(args.getObjects("requirements"));
        List<Map<String, Object>> warnings = new ArrayList<>();
        boolean suggested = false;
        String compilerNote = null;
        try {
            CompileRequirementsResponse compiled = compilerService.compile(new CompileRequirementsRequest(
                    args.getString("title"), description, level));
            for (CompileRequirementsResponse.CompileWarning warning : compiled.warnings()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("phrase", warning.phrase());
                item.put("kind", warning.kind());
                item.put("suggestion", warning.suggestion());
                warnings.add(item);
            }
            if (requirements.isEmpty()) {
                for (CompileRequirementsResponse.CompiledRequirement requirement : compiled.requirements()) {
                    if (requirements.size() >= 30) break;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", requirement.type().name());
                    item.put("description", requirement.description());
                    item.put("mandatory", requirement.mandatory());
                    item.put("knockout", requirement.knockout());
                    item.put("minimumYears", requirement.minimumYears());
                    item.put("alternatives", requirement.alternatives());
                    item.put("proficiency", requirement.proficiency() == null ? null : requirement.proficiency().name());
                    requirements.add(item);
                }
                suggested = !requirements.isEmpty();
            }
        } catch (RuntimeException ex) {
            log.info("Requirement compiler unavailable for an assistant job preview: {}", ex.getClass().getSimpleName());
            compilerNote = "The wording check is unavailable right now; review the text yourself.";
        }

        Map<String, Object> merged = new LinkedHashMap<>(args.values());
        merged.put("description", description);
        merged.put("requirements", requirements);
        merged.putIfAbsent("openings", 1L);
        merged.putIfAbsent(PUBLISH, false);
        // Validated exactly like on confirm (suggested requirements included), so the preview is what will be created.
        ToolArgs validated = ToolArgs.validate(args(), merged);
        Map<String, Object> normalised = new LinkedHashMap<>(validated.values());
        requirements = new ArrayList<>(validated.getObjects("requirements"));

        Map<String, Object> draft = new LinkedHashMap<>();
        for (String key : List.of("title", "department", "location", "jobType", "experienceLevel", "salaryMin",
                "salaryMax", "openings", "deadline", "summary", "responsibilities", "description")) {
            draft.put(key, normalised.get(key));
        }
        draft.put("requirements", requirements);
        draft.put("requirementsSuggested", suggested);
        draft.put("warnings", warnings);
        draft.put("compilerNote", compilerNote);
        draft.put("modes", List.of("DRAFT", "PUBLISH"));
        draft.put("defaultMode", Boolean.TRUE.equals(normalised.get(PUBLISH)) ? "PUBLISH" : "DRAFT");

        Map<String, Object> jobPreview = new LinkedHashMap<>();
        jobPreview.put("kind", "job_preview");
        jobPreview.put("draft", draft);

        String summary = "Create the job \"" + args.getString("title") + "\" (" + args.getString("location")
                + ") as a draft or publish it";
        return new ActionProposal(summary, normalised, draft, List.of(jobPreview));
    }

    @Override
    public ActionOutcome execute(ToolContext context, ToolArgs args) {
        validate(args);
        boolean publish = args.getBoolean(PUBLISH, false);
        JobCreateRequest request = new JobCreateRequest(args.getString("title"), description(args),
                args.getString("department"), args.getString("location"), JobType.valueOf(args.getString("jobType")),
                ExperienceLevel.valueOf(args.getString("experienceLevel")), args.getDecimal("salaryMin"),
                args.getDecimal("salaryMax"), args.has("openings") ? args.getInt("openings") : 1,
                args.getDateTime("deadline"));
        JobStaffResponse job = jobService.create(request);
        List<RequirementInput> requirements = new ArrayList<>();
        for (Map<String, Object> item : args.getObjects("requirements")) {
            requirements.add(requirementInput(item));
        }
        if (!requirements.isEmpty()) {
            job = jobService.replaceRequirements(job.id(), new RequirementsReplaceRequest(requirements));
        }
        if (publish) {
            job = jobService.transition(job.id(), JobStatus.PUBLISHED);
        }
        String message = publish
                ? "Published the job \"" + job.title() + "\" with " + requirements.size() + " requirements."
                : "Saved the job \"" + job.title() + "\" as a draft with " + requirements.size() + " requirements.";
        return new ActionOutcome("job", job.id(), Tools.jobLink(job.id()), message, "JOB", String.valueOf(job.id()),
                "Job #" + job.id() + " created (mode " + (publish ? "PUBLISH" : "DRAFT") + ", " + requirements.size()
                        + " requirements)");
    }

    private static String description(ToolArgs args) {
        String description = args.getString("description");
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        StringBuilder text = new StringBuilder();
        String summary = args.getString("summary");
        if (summary != null && !summary.isBlank()) {
            text.append(summary.trim());
        }
        List<String> responsibilities = args.getStrings("responsibilities");
        if (!responsibilities.isEmpty()) {
            if (text.length() > 0) text.append("\n\n");
            text.append("Responsibilities:");
            for (String responsibility : responsibilities) {
                text.append("\n- ").append(responsibility);
            }
        }
        if (text.length() == 0) {
            throw new AssistantToolException("A job needs a summary or a description");
        }
        if (text.length() > 50_000) {
            throw new AssistantToolException("The job description must be at most 50,000 characters");
        }
        return text.toString();
    }

    private static void validate(ToolArgs args) {
        BigDecimal min = args.getDecimal("salaryMin");
        BigDecimal max = args.getDecimal("salaryMax");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new AssistantToolException("The minimum salary cannot exceed the maximum salary");
        }
        LocalDateTime deadline = args.getDateTime("deadline");
        if (deadline != null && !deadline.isAfter(UtcTime.now())) {
            throw new AssistantToolException("The application deadline must be in the future");
        }
    }

    @SuppressWarnings("unchecked")
    private static RequirementInput requirementInput(Map<String, Object> item) {
        Object years = item.get("minimumYears");
        Object proficiency = item.get("proficiency");
        Object alternatives = item.get("alternatives");
        return new RequirementInput(null, RequirementType.valueOf(String.valueOf(item.get("type"))),
                String.valueOf(item.get("description")),
                item.get("mandatory") instanceof Boolean mandatory ? mandatory : Boolean.TRUE,
                item.get("knockout") instanceof Boolean knockout ? knockout : null,
                years instanceof Number number ? number.intValue() : null,
                alternatives instanceof List<?> list ? (List<String>) list : List.of(),
                proficiency == null ? null : Proficiency.valueOf(String.valueOf(proficiency)));
    }
}
