package com.recruitpro.notification.dto;

import com.recruitpro.notification.entity.Notification;

import java.time.LocalDateTime;

public record NotificationResponse(Long id, String template, String subject, String body,
                                   LocalDateTime createdAt, LocalDateTime readAt) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getTemplate().name(), notification.getSubject(),
                notification.getBody(), notification.getCreatedAt(), notification.getReadAt());
    }
}
