package com.recruitpro.notification.entity;

/** EMAIL rows form the outbox; IN_APP rows form the user's inbox. WhatsApp has no provider yet (D13). */
public enum NotificationChannel {
    EMAIL,
    IN_APP
}
