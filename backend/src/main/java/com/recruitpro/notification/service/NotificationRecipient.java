package com.recruitpro.notification.service;

import com.recruitpro.user.entity.User;

/** Who receives a notification: a registered user (inbox + email) or an email address only. */
public record NotificationRecipient(Long userId, String email, Long organizationId) {

    public static NotificationRecipient of(User user) {
        return new NotificationRecipient(user.getId(), user.getEmail(), user.getOrganizationId());
    }

    public static NotificationRecipient email(String email, Long organizationId) {
        return new NotificationRecipient(null, email, organizationId);
    }
}
