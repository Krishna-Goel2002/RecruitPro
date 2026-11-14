package com.recruitpro.audit.dto;

import com.recruitpro.audit.entity.AuditEvent;

import java.time.LocalDateTime;

public record AuditEventResponse(
        Long id,
        String action,
        String actorEmail,
        Long actorUserId,
        String targetType,
        String targetId,
        String details,
        String ipAddress,
        LocalDateTime createdAt
) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getAction(), event.getActorEmail(), event.getActorUserId(),
                event.getTargetType(), event.getTargetId(), event.getDetails(), event.getIpAddress(), event.getCreatedAt());
    }
}
