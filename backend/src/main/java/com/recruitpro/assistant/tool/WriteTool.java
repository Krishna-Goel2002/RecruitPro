package com.recruitpro.assistant.tool;

/**
 * A write. Never runs during planning: {@link #prepare} checks permissions and the current state without changing
 * anything and returns a preview, which becomes a PENDING proposed action; {@link #execute} runs only after the user
 * confirms, with re-validated arguments, through the existing service methods (which check everything again).
 */
public interface WriteTool extends AssistantTool {

    @Override
    default boolean writes() {
        return true;
    }

    ActionProposal prepare(ToolContext context, ToolArgs args);

    ActionOutcome execute(ToolContext context, ToolArgs args);

    /**
     * Whether {@link #execute} and its audit row run in one transaction (all or nothing). False for the AI drafting
     * tools, whose services must not hold a database transaction while the AI service works.
     */
    default boolean transactional() {
        return true;
    }
}
