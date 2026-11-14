package com.recruitpro.notification.service;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationStatus;
import com.recruitpro.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delivers PENDING EMAIL notifications. With {@code app.mail.enabled=true} it sends through
 * {@link JavaMailSender} (Mailpit in development); otherwise it marks them SKIPPED and logs the subject and
 * links at INFO. Links keep their one-time tokens in that log line only when
 * {@code app.notifications.log-links=true} (development); one-time tokens are removed from the stored body
 * after delivery.
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);
    private static final Pattern TOKEN_PARAMETER = Pattern.compile("(token=)[A-Za-z0-9_\\-]+");
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final int BATCH_SIZE = 20;

    private final NotificationRepository repository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final TransactionTemplate transactionTemplate;
    private final boolean senderEnabled;
    private final boolean mailEnabled;
    private final boolean logLinks;
    private final String from;
    private final int maxAttempts;

    public NotificationSender(NotificationRepository repository,
                              ObjectProvider<JavaMailSender> mailSenderProvider,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.notifications.sender-enabled:true}") boolean senderEnabled,
                              @Value("${app.mail.enabled:false}") boolean mailEnabled,
                              @Value("${app.notifications.log-links:false}") boolean logLinks,
                              @Value("${app.mail.from:RecruitPro <no-reply@recruitpro.local>}") String from,
                              @Value("${app.notifications.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.mailSenderProvider = mailSenderProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.senderEnabled = senderEnabled;
        this.mailEnabled = mailEnabled;
        this.logLinks = logLinks;
        this.from = from;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.notifications.poll-interval-ms:5000}",
            initialDelayString = "${app.notifications.initial-delay-ms:5000}")
    public void poll() {
        if (!senderEnabled) {
            return;
        }
        try {
            sendPendingBatch();
        } catch (RuntimeException ex) {
            log.warn("Notification delivery run failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Delivers one batch of pending emails; returns the number processed. */
    public int sendPendingBatch() {
        List<Long> ids = transactionTemplate.execute(status -> repository
                .findAllByChannelAndStatusOrderByCreatedAtAscIdAsc(NotificationChannel.EMAIL, NotificationStatus.PENDING,
                        PageRequest.of(0, BATCH_SIZE))
                .stream().map(Notification::getId).toList());
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        for (Long id : ids) {
            transactionTemplate.executeWithoutResult(status -> deliver(id));
        }
        return ids.size();
    }

    private void deliver(Long id) {
        Notification notification = repository.findById(id).orElse(null);
        if (notification == null || notification.getStatus() != NotificationStatus.PENDING) {
            return;
        }
        if (!mailEnabled) {
            String body = logLinks ? notification.getBody() : redact(notification.getBody());
            log.info("Email delivery disabled (app.mail.enabled=false): {} to {} subject='{}' links={}",
                    notification.getTemplate(), notification.getRecipientEmail(), notification.getSubject(),
                    links(body));
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setSentAt(UtcTime.now());
            notification.setBody(redact(notification.getBody()));
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("app.mail.enabled=true but no JavaMailSender is configured (spring.mail.host); notification {} failed", id);
            notification.setAttempts(notification.getAttempts() + 1);
            notification.setStatus(NotificationStatus.FAILED);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(notification.getRecipientEmail());
            message.setSubject(notification.getSubject());
            message.setText(notification.getBody());
            mailSender.send(message);
            notification.setAttempts(notification.getAttempts() + 1);
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(UtcTime.now());
            notification.setBody(redact(notification.getBody()));
        } catch (MailException ex) {
            int attempts = notification.getAttempts() + 1;
            notification.setAttempts(attempts);
            if (attempts >= maxAttempts) {
                notification.setStatus(NotificationStatus.FAILED);
            }
            log.warn("Sending notification {} failed (attempt {}): {}", id, attempts, ex.getClass().getSimpleName());
        }
    }

    /** Removes one-time tokens from links once a message has left the outbox. */
    static String redact(String body) {
        if (body == null) {
            return null;
        }
        return TOKEN_PARAMETER.matcher(body).replaceAll("$1[removed-after-delivery]");
    }

    private static List<String> links(String body) {
        List<String> links = new ArrayList<>();
        if (body == null) {
            return links;
        }
        Matcher matcher = LINK.matcher(body);
        while (matcher.find()) {
            links.add(matcher.group());
        }
        return links;
    }
}
