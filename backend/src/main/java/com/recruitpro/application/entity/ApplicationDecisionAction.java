package com.recruitpro.application.entity;

/**
 * Actions recorded in the decision history (plan 3.3). SHORTLIST, REJECT, UNDO_SHORTLIST, REOPEN,
 * MOVE_TO_INTERVIEW and SELECT are HR decisions; COMMENT_UPDATED, WITHDRAW (candidate) and
 * IDENTITY_REVEALED are recorded events.
 */
public enum ApplicationDecisionAction {
    SHORTLIST, REJECT, COMMENT_UPDATED, UNDO_SHORTLIST, REOPEN, MOVE_TO_INTERVIEW, SELECT, WITHDRAW, IDENTITY_REVEALED;

    /** True for the actions an HR manager may request through the decisions endpoint. */
    public boolean isDecision() {
        return switch (this) {
            case SHORTLIST, REJECT, UNDO_SHORTLIST, REOPEN, MOVE_TO_INTERVIEW, SELECT -> true;
            case COMMENT_UPDATED, WITHDRAW, IDENTITY_REVEALED -> false;
        };
    }
}
