package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;

import java.time.LocalDateTime;

/**
 * DecisionEvent (plan 5.4). {@code matchRunId} is the match run that was current when the event was recorded
 * (D8). For events by the candidate (WITHDRAW) before the identity is revealed, {@code actorName} is the blind
 * display name and {@code actorUserId} is omitted.
 */
public record DecisionEventResponse(Long id, Long applicationId, ApplicationStatus previousStatus, ApplicationStatus newStatus,
                                    String actionType, Long actorUserId, String actorName, LocalDateTime timestamp,
                                    String comment, Long matchRunId) {

    public static DecisionEventResponse from(ApplicationDecisionHistory event, boolean identityRevealed, String blindName) {
        User actor = event.getActor();
        boolean hideActor = actor != null && actor.getRole() == Role.CANDIDATE && !identityRevealed;
        return new DecisionEventResponse(event.getId(), event.getApplication().getId(), event.getPreviousStatus(),
                event.getNewStatus(), event.getActionType().name(),
                actor == null || hideActor ? null : actor.getId(),
                actor == null ? null : hideActor ? blindName : actor.getFullName(),
                event.getCreatedAt(), event.getComment(), event.getMatchRunId());
    }
}
