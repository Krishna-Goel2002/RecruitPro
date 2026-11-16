package com.recruitpro.assistant.tool;

import java.util.Map;

/** A read-only tool: runs during planning and returns de-identified observation data (codes, never names). */
public interface ReadTool extends AssistantTool {

    /**
     * Runs as {@code context.user()}. Throws {@link AssistantToolException} (or the services' 400/403/404 exceptions)
     * when the input or the user's permissions do not allow it; the message becomes an observation error.
     */
    Map<String, Object> run(ToolContext context, ToolArgs args);
}
