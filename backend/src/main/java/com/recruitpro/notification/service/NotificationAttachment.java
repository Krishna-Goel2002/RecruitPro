package com.recruitpro.notification.service;

/**
 * A text attachment of an outbox e-mail (WP-PLAT), e.g. {@code invite.ics} with
 * {@code text/calendar; charset=UTF-8; method=REQUEST}. Stored on the EMAIL row only, never on the inbox copy.
 */
public record NotificationAttachment(String fileName, String contentType, String content) {

    public NotificationAttachment {
        if (fileName == null || fileName.isBlank() || fileName.length() > 120) {
            throw new IllegalArgumentException("An attachment needs a file name of at most 120 characters");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 120) {
            throw new IllegalArgumentException("An attachment needs a content type of at most 120 characters");
        }
        if (content == null) {
            throw new IllegalArgumentException("An attachment needs content");
        }
    }
}
