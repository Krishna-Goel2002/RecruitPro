package com.recruitpro.admin.dto;

import java.time.LocalDateTime;

public record AdminAuditRecord(
        String id,
        String action,
        String actorEmail,
        Long actorUserId,
        String targetResourceType,
        String targetResourceId,
        String details,
        LocalDateTime timestamp
) { }
