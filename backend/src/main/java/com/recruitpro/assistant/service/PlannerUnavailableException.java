package com.recruitpro.assistant.service;

/** The planner could not answer (not configured, unreachable, failing, timed out or an unusable reply). */
public class PlannerUnavailableException extends Exception {

    private final boolean timedOut;

    public PlannerUnavailableException(String message, boolean timedOut) {
        super(message);
        this.timedOut = timedOut;
    }

    public boolean isTimedOut() {
        return timedOut;
    }
}
