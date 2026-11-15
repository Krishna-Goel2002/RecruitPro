package com.recruitpro.notification.service;

import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.notification.dto.NotificationResponse;
import com.recruitpro.notification.dto.UnreadCountResponse;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationStatus;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * D13: notifications through an outbox. {@link #notify} writes an EMAIL row (delivered later by
 * {@link NotificationSender}) and, for registered users and inbox-worthy templates, an IN_APP row. Rows are
 * written in the caller's transaction, so a notification exists exactly when its triggering change commits.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final SecurityUtils securityUtils;

    public NotificationService(NotificationRepository repository, SecurityUtils securityUtils) {
        this.repository = repository;
        this.securityUtils = securityUtils;
    }

    @Transactional
    public List<Notification> notify(NotificationRecipient recipient, NotificationTemplate template, Map<String, String> params) {
        return notify(recipient, template, params, null);
    }

    /** As {@link #notify(NotificationRecipient, NotificationTemplate, Map)} with an attachment on the e-mail (WP-PLAT). */
    @Transactional
    public List<Notification> notify(NotificationRecipient recipient, NotificationTemplate template, Map<String, String> params,
                                     NotificationAttachment attachment) {
        if (recipient == null || recipient.email() == null || recipient.email().isBlank() || template == null) {
            throw new IllegalArgumentException("A recipient email and template are required");
        }
        String subject = template.renderSubject(params);
        String body = template.renderBody(params);
        List<Notification> created = new ArrayList<>(2);
        Notification email = build(recipient, template, NotificationChannel.EMAIL, NotificationStatus.PENDING, subject, body);
        if (attachment != null) {
            email.setAttachmentName(attachment.fileName());
            email.setAttachmentContentType(attachment.contentType());
            email.setAttachmentContent(attachment.content());
        }
        created.add(repository.save(email));
        if (template.isInApp() && recipient.userId() != null) {
            Notification inApp = build(recipient, template, NotificationChannel.IN_APP, NotificationStatus.SENT, subject, body);
            inApp.setSentAt(UtcTime.now());
            created.add(repository.save(inApp));
        }
        return created;
    }

    // ---- Inbox of the current user (/api/v1/me/notifications) ----

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listMine(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
        }
        CurrentUser user = securityUtils.currentUser();
        Page<Notification> result = repository.findAllByRecipientUserIdAndChannel(user.userId(), NotificationChannel.IN_APP,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.<NotificationResponse>builder()
                .content(result.getContent().stream().map(NotificationResponse::from).toList())
                .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        CurrentUser user = securityUtils.currentUser();
        return new UnreadCountResponse(repository.countByRecipientUserIdAndChannelAndReadAtIsNull(user.userId(), NotificationChannel.IN_APP));
    }

    @Transactional
    public void markRead(Long notificationId) {
        CurrentUser user = securityUtils.currentUser();
        Notification notification = repository.findByIdAndRecipientUserIdAndChannel(notificationId, user.userId(), NotificationChannel.IN_APP)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(UtcTime.now());
        }
    }

    @Transactional
    public void markAllRead() {
        CurrentUser user = securityUtils.currentUser();
        repository.markAllRead(user.userId(), NotificationChannel.IN_APP, UtcTime.now());
    }

    private Notification build(NotificationRecipient recipient, NotificationTemplate template, NotificationChannel channel,
                               NotificationStatus status, String subject, String body) {
        Notification notification = new Notification();
        notification.setOrganizationId(recipient.organizationId());
        notification.setRecipientUserId(recipient.userId());
        notification.setRecipientEmail(recipient.email().trim().toLowerCase(Locale.ROOT));
        notification.setChannel(channel);
        notification.setTemplate(template);
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setStatus(status);
        notification.setAttempts(0);
        return notification;
    }
}
