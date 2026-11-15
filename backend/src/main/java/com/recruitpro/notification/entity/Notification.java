package com.recruitpro.notification.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** D13: notification outbox (EMAIL) and in-app inbox (IN_APP). */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 60)
    private NotificationTemplate template;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** Sender instance that is delivering the row (WP-PLAT row claim; several backend instances). */
    @Column(name = "claimed_by", length = 80)
    private String claimedBy;

    @Column(name = "claimed_until")
    private LocalDateTime claimedUntil;

    /** Optional e-mail attachment (e.g. {@code invite.ics}); EMAIL rows only. */
    @Column(name = "attachment_name", length = 120)
    private String attachmentName;

    @Column(name = "attachment_content_type", length = 120)
    private String attachmentContentType;

    @Column(name = "attachment_content", columnDefinition = "TEXT")
    private String attachmentContent;
}
