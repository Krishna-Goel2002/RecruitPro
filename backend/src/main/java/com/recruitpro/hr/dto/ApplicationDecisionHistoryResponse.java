package com.recruitpro.hr.dto;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import java.time.LocalDateTime;

public record ApplicationDecisionHistoryResponse(Long id, Long applicationId, ApplicationStatus previousStatus,
        ApplicationStatus newStatus, String actionType, Long actorUserId, String actorName,
        LocalDateTime timestamp, String comment) {
    public static ApplicationDecisionHistoryResponse from(ApplicationDecisionHistory event) {
        String name = (event.getActor().getFirstName() + " " + event.getActor().getLastName()).trim();
        return new ApplicationDecisionHistoryResponse(event.getId(), event.getApplication().getId(),
                event.getPreviousStatus(), event.getNewStatus(), event.getActionType().name(),
                event.getActor().getId(), name, event.getCreatedAt(), event.getComment());
    }
}
