package com.recruitpro.assistant.tool;

/**
 * A tool refused its input or the current state does not allow it. The message is safe to show to the user and to
 * send to the planner as an observation error (it never contains personal data).
 */
public class AssistantToolException extends RuntimeException {

    public AssistantToolException(String message) {
        super(message);
    }
}
