package com.recruitpro.application.service;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Application state machine (plan 3.4). HR decisions:
 * SUBMITTED -SHORTLIST-> SHORTLISTED (auto-reveal), SUBMITTED -REJECT-> REJECTED,
 * SHORTLISTED -UNDO_SHORTLIST-> SUBMITTED (comment required), SHORTLISTED -MOVE_TO_INTERVIEW-> INTERVIEWING,
 * SHORTLISTED/INTERVIEWING -REJECT-> REJECTED, INTERVIEWING -SELECT-> SELECTED (terminal),
 * REJECTED -REOPEN-> SUBMITTED (comment required). Candidates may withdraw from SUBMITTED, SHORTLISTED and
 * INTERVIEWING.
 */
public final class ApplicationTransitions {

    /** Order in which allowed decisions are listed (allowedActions). */
    private static final List<ApplicationDecisionAction> DECISIONS = List.of(ApplicationDecisionAction.SHORTLIST,
            ApplicationDecisionAction.MOVE_TO_INTERVIEW, ApplicationDecisionAction.SELECT, ApplicationDecisionAction.REJECT,
            ApplicationDecisionAction.UNDO_SHORTLIST, ApplicationDecisionAction.REOPEN);

    public static final Set<ApplicationStatus> WITHDRAWABLE = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING);

    private ApplicationTransitions() {
    }

    /** Status reached by applying the decision, or null when the decision is not allowed from {@code from}. */
    public static ApplicationStatus target(ApplicationStatus from, ApplicationDecisionAction action) {
        if (from == null || action == null) {
            return null;
        }
        return switch (action) {
            case SHORTLIST -> from == ApplicationStatus.SUBMITTED ? ApplicationStatus.SHORTLISTED : null;
            case REJECT -> from == ApplicationStatus.SUBMITTED || from == ApplicationStatus.SHORTLISTED
                    || from == ApplicationStatus.INTERVIEWING ? ApplicationStatus.REJECTED : null;
            case UNDO_SHORTLIST -> from == ApplicationStatus.SHORTLISTED ? ApplicationStatus.SUBMITTED : null;
            case MOVE_TO_INTERVIEW -> from == ApplicationStatus.SHORTLISTED ? ApplicationStatus.INTERVIEWING : null;
            case SELECT -> from == ApplicationStatus.INTERVIEWING ? ApplicationStatus.SELECTED : null;
            case REOPEN -> from == ApplicationStatus.REJECTED ? ApplicationStatus.SUBMITTED : null;
            case COMMENT_UPDATED, WITHDRAW, IDENTITY_REVEALED -> null;
        };
    }

    /** HR decisions allowed from the status, in display order. */
    public static List<ApplicationDecisionAction> allowedDecisions(ApplicationStatus from) {
        List<ApplicationDecisionAction> allowed = new ArrayList<>();
        for (ApplicationDecisionAction action : DECISIONS) {
            if (target(from, action) != null) {
                allowed.add(action);
            }
        }
        return allowed;
    }

    /** UNDO_SHORTLIST and REOPEN reverse an earlier decision and must be explained. */
    public static boolean requiresComment(ApplicationDecisionAction action) {
        return action == ApplicationDecisionAction.UNDO_SHORTLIST || action == ApplicationDecisionAction.REOPEN;
    }

    public static boolean candidateMayWithdraw(ApplicationStatus status) {
        return status != null && WITHDRAWABLE.contains(status);
    }
}
