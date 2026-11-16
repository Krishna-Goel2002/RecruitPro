package com.recruitpro.assistant.tool;

import java.util.List;
import java.util.Map;

/**
 * What {@link WriteTool#prepare} returns: a one-line {@code summary}, the normalised arguments stored with the action
 * (re-validated on confirm), the {@code preview} shown to the user (never sent to the planner) and extra blocks shown
 * before the action block (the {@code job_preview} of create_job).
 */
public record ActionProposal(String summary, Map<String, Object> args, Map<String, Object> preview,
                             List<Map<String, Object>> extraBlocks) {
}
