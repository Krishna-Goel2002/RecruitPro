package com.recruitpro.assistant.tool;

import com.recruitpro.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tool whitelist (every {@link AssistantTool} bean), in a fixed catalogue order: read tools first, then writes.
 */
@Component
public class ToolRegistry {

    /** Catalogue order (ASSISTANT_PLAN 3.2). Tools not listed follow alphabetically. */
    static final List<String> ORDER = List.of("list_jobs", "job_insights", "applicant_insights", "compare_applicants",
            "attention_digest", "my_interviews", "fairness_summary", "explain", "create_job", "update_job_status",
            "decide_application", "assign_work_sample", "schedule_interview", "generate_work_sample",
            "generate_interview_kit");

    private final Map<String, AssistantTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AssistantTool> beans) {
        List<AssistantTool> sorted = new ArrayList<>(beans);
        sorted.sort(Comparator.comparingInt((AssistantTool tool) -> {
            int index = ORDER.indexOf(tool.name());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(AssistantTool::name));
        for (AssistantTool tool : sorted) {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate assistant tool name: " + tool.name());
            }
        }
    }

    /** The tools the user may use (their role's catalogue). */
    public List<AssistantTool> catalogueFor(CurrentUser user) {
        List<AssistantTool> result = new ArrayList<>();
        for (AssistantTool tool : tools.values()) {
            if (tool.availableTo(user)) {
                result.add(tool);
            }
        }
        return result;
    }

    /** The tool when it exists and the user may use it. */
    public Optional<AssistantTool> find(String name, CurrentUser user) {
        AssistantTool tool = name == null ? null : tools.get(name.trim());
        return tool != null && tool.availableTo(user) ? Optional.of(tool) : Optional.empty();
    }

    public List<AssistantTool> all() {
        return List.copyOf(tools.values());
    }
}
