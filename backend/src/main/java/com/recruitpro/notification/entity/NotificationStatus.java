package com.recruitpro.notification.entity;

/** PENDING (waiting for the sender), SENT, FAILED (attempts exhausted), SKIPPED (mail disabled). */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}
