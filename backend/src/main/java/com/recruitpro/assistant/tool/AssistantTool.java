package com.recruitpro.assistant.tool;

import com.recruitpro.security.CurrentUser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool of the assistant's whitelist (ASSISTANT_PLAN 3.2). The planner sees {@link #catalogueEntry()} for the tools
 * the user's role allows and can only name those; the backend validates every argument ({@link ToolArgs}) and runs
 * the tool as the signed-in user through the existing services. Read tools are {@link ReadTool}; writes are
 * {@link WriteTool} and only ever become proposed actions.
 */
public interface AssistantTool {

    String name();

    String description();

    List<ArgSpec> args();

    ToolCapability capability();

    default boolean writes() {
        return false;
    }

    default boolean availableTo(CurrentUser user) {
        return user != null && user.organizationId() != null && capability().allows(user.role());
    }

    /** {name, description, args: {name: "type, required|optional, ..."}, writes} for the planner. */
    default Map<String, Object> catalogueEntry() {
        Map<String, Object> args = new LinkedHashMap<>();
        for (ArgSpec spec : args()) {
            args.put(spec.name(), spec.describe());
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name());
        entry.put("description", description());
        entry.put("args", args);
        entry.put("writes", writes());
        return entry;
    }
}
