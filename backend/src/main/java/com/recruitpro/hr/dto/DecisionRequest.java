package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationDecisionAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST …/decisions: {action, comment?}. action is SHORTLIST, REJECT, UNDO_SHORTLIST, REOPEN, MOVE_TO_INTERVIEW or
 * SELECT; a comment is required for UNDO_SHORTLIST and REOPEN.
 */
public record DecisionRequest(@NotNull ApplicationDecisionAction action, @Size(max = 2000) String comment) {
}
