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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ByteArrayResource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    /** Referee links carry their one-time token as a path segment: {@code <candidate-url>/reference/<token>}. */
    private static final Pattern REFERENCE_TOKEN = Pattern.compile("(/reference/)[A-Za-z0-9_\\-]{16,}");
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final int BATCH_SIZE = 20;
    /** How long a sender instance holds a claimed row; an instance that dies mid-send releases it by expiry. */
    static final Duration CLAIM_DURATION = Duration.ofMinutes(2);
    /** Wait before the next attempt after a failed send: 1, 2, 4, 8 ... minutes, at most one hour. */
    static final Duration RETRY_BASE = Duration.ofMinutes(1);
    static final Duration RETRY_MAX = Duration.ofHours(1);

    private final NotificationRepository repository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final TransactionTemplate transactionTemplate;
    private final boolean senderEnabled;
    private final boolean mailEnabled;
    private final boolean logLinks;
    private final String from;
    private final int maxAttempts;
    private final String instanceId;

    public NotificationSender(NotificationRepository repository,
                              ObjectProvider<JavaMailSender> mailSenderProvider,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.notifications.sender-enabled:true}") boolean senderEnabled,
                              @Value("${app.mail.enabled:false}") boolean mailEnabled,
                              @Value("${app.notifications.log-links:false}") boolean logLinks,
                              @Value("${app.mail.from:RecruitPro <no-reply@recruitpro.local>}") String from,
                              @Value("${app.notifications.max-attempts:5}") int maxAttempts,
                              @Value("${app.instance-id:}") String instanceId) {
        this.repository = repository;
        this.mailSenderProvider = mailSenderProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.senderEnabled = senderEnabled;
        this.mailEnabled = mailEnabled;
        this.logLinks = logLinks;
        this.from = from;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.instanceId = instanceId == null || instanceId.isBlank() ? defaultInstanceId() : limit(instanceId.trim(), 80);
    }

    /** Host name plus a random suffix, so two processes on one host never share an id. */
    static String defaultInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "backend";
        }
        return limit(host, 60) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    public String instanceId() {
        return instanceId;
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

    /**
     * Delivers one batch of pending emails; returns the number this instance processed. Each row is first claimed
     * with a conditional update (WP-PLAT: {@code claimed_by}, {@code claimed_until}) in its own transaction, so two
     * backend instances never send the same message; the claim is released when the row is updated after sending.
     */
    public int sendPendingBatch() {
        List<Long> ids = transactionTemplate.execute(status -> repository.findUnclaimedIds(NotificationChannel.EMAIL,
                NotificationStatus.PENDING, UtcTime.now(), PageRequest.of(0, BATCH_SIZE)));
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (Long id : ids) {
            if (!claim(id)) {
                continue;
            }
            if (deliver(id)) {
                processed++;
            }
        }
        return processed;
    }

    /** Claims one pending row for this instance; false when another instance holds it or it is no longer pending. */
    public boolean claim(Long id) {
        Integer updated = transactionTemplate.execute(status -> {
            LocalDateTime now = UtcTime.now();
            return repository.claim(id, NotificationStatus.PENDING, instanceId, now.plus(CLAIM_DURATION), now);
        });
        return updated != null && updated == 1;
    }

    /**
     * Delivers one claimed row. The row is read in one short transaction, the message is sent with no transaction (and
     * no database connection) held open during the SMTP exchange, and the outcome is written in a second transaction.
     * Returns false when the row was no longer pending or no longer held by this instance.
     */
    private boolean deliver(Long id) {
        Notification notification = transactionTemplate.execute(status -> repository.findById(id)
                .filter(row -> row.getStatus() == NotificationStatus.PENDING && instanceId.equals(row.getClaimedBy()))
                .orElse(null));
        if (notification == null) {
            return false;
        }
        if (!mailEnabled) {
            String body = logLinks ? notification.getBody() : redact(notification.getBody());
            log.info("Email delivery disabled (app.mail.enabled=false): {} to {} subject='{}' links={} attachment={}",
                    notification.getTemplate(), notification.getRecipientEmail(), notification.getSubject(),
                    links(body), notification.getAttachmentName());
            recordOutcome(id, Outcome.SKIPPED);
            return true;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("app.mail.enabled=true but no JavaMailSender is configured (spring.mail.host); notification {} failed", id);
            recordOutcome(id, Outcome.MISCONFIGURED);
            return true;
        }
        Outcome outcome;
        try {
            if (notification.getAttachmentName() != null && notification.getAttachmentContent() != null) {
                mailSender.send(mimeMessage(mailSender, notification));
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(notification.getRecipientEmail());
                message.setSubject(notification.getSubject());
                message.setText(notification.getBody());
                mailSender.send(message);
            }
            outcome = Outcome.SENT;
        } catch (MessagingException | RuntimeException ex) {
            // Any failure of one message (SMTP, an unparsable address, ...) counts as an attempt of that message only;
            // it must neither abort the rest of the batch nor be retried forever without counting.
            log.warn("Sending notification {} failed (attempt {}): {}", id, notification.getAttempts() + 1,
                    ex.getClass().getSimpleName());
            outcome = Outcome.FAILED_ATTEMPT;
        }
        recordOutcome(id, outcome);
        return true;
    }

    private enum Outcome { SENT, SKIPPED, MISCONFIGURED, FAILED_ATTEMPT }

    /** Writes the delivery outcome and releases (or, for a retry, extends) this instance's claim. */
    private void recordOutcome(Long id, Outcome outcome) {
        transactionTemplate.executeWithoutResult(status -> repository.findById(id).ifPresent(notification -> {
            if (notification.getStatus() != NotificationStatus.PENDING || !instanceId.equals(notification.getClaimedBy())) {
                return;
            }
            LocalDateTime now = UtcTime.now();
            notification.setClaimedBy(null);
            notification.setClaimedUntil(null);
            switch (outcome) {
                case SKIPPED -> {
                    notification.setStatus(NotificationStatus.SKIPPED);
                    notification.setSentAt(now);
                    notification.setBody(redact(notification.getBody()));
                }
                case SENT -> {
                    notification.setAttempts(notification.getAttempts() + 1);
                    notification.setStatus(NotificationStatus.SENT);
                    notification.setSentAt(now);
                    notification.setBody(redact(notification.getBody()));
                }
                case MISCONFIGURED -> {
                    notification.setAttempts(notification.getAttempts() + 1);
                    notification.setStatus(NotificationStatus.FAILED);
                }
                case FAILED_ATTEMPT -> {
                    int attempts = notification.getAttempts() + 1;
                    notification.setAttempts(attempts);
                    if (attempts >= maxAttempts) {
                        notification.setStatus(NotificationStatus.FAILED);
                    } else {
                        // Back off: the row stays held by this instance until the retry time, so neither this nor
                        // another instance picks it up again within seconds (a short SMTP outage would otherwise use
                        // up every attempt in under half a minute and drop the message).
                        notification.setClaimedBy(instanceId);
                        notification.setClaimedUntil(now.plus(retryDelay(attempts)));
                    }
                }
            }
        }));
    }

    /** Delay before the next attempt after {@code attempts} failed attempts: min(1 min x 2^(attempts-1), 1 h). */
    static Duration retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 10));
        Duration delay = RETRY_BASE.multipliedBy(1L << exponent);
        return delay.compareTo(RETRY_MAX) > 0 ? RETRY_MAX : delay;
    }

    /** Plain-text body plus the stored attachment (calendar invites: {@code text/calendar; method=...}). */
    private MimeMessage mimeMessage(JavaMailSender mailSender, Notification notification) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(notification.getRecipientEmail());
        helper.setSubject(notification.getSubject());
        helper.setText(notification.getBody(), false);
        String contentType = notification.getAttachmentContentType() == null ? "application/octet-stream"
                : notification.getAttachmentContentType();
        helper.addAttachment(notification.getAttachmentName(),
                new ByteArrayResource(notification.getAttachmentContent().getBytes(StandardCharsets.UTF_8)), contentType);
        return message;
    }

    /** Removes one-time tokens from links once a message has left the outbox. */
    static String redact(String body) {
        if (body == null) {
            return null;
        }
        String redacted = TOKEN_PARAMETER.matcher(body).replaceAll("$1[removed-after-delivery]");
        return REFERENCE_TOKEN.matcher(redacted).replaceAll("$1[removed-after-delivery]");
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
