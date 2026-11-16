package com.recruitpro.notification;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationStatus;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.notification.service.NotificationAttachment;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationSender;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Notification sender row claims (several backend instances) and e-mail attachments on the outbox row only. */
@SpringBootTest
@ActiveProfiles("test")
class NotificationClaimIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository repository;
    @Autowired NotificationSender sender;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    private Long pendingEmail() {
        List<Notification> rows = notificationService.notify(NotificationRecipient.email("someone@example.test", null),
                NotificationTemplate.INTERVIEW_CANCELLED, Map.of("firstName", "Sam", "jobTitle", "Engineer"),
                new NotificationAttachment("cancel.ics", "text/calendar; charset=UTF-8; method=CANCEL", "BEGIN:VCALENDAR\r\n"));
        return rows.get(0).getId();
    }

    private int claim(Long id, String instance, LocalDateTime now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer updated = tx.execute(status -> repository.claim(id, NotificationStatus.PENDING, instance, now.plusMinutes(2), now));
        return updated == null ? 0 : updated;
    }

    @Test
    void onlyOneInstanceCanHoldARowUntilTheClaimExpires() {
        Long id = pendingEmail();
        LocalDateTime now = UtcTime.now();
        assertEquals(1, claim(id, "instance-a", now));
        assertEquals(0, claim(id, "instance-b", now), "a held row cannot be claimed again");
        assertEquals(0, claim(id, "instance-b", now.plusMinutes(1)));
        assertEquals(1, claim(id, "instance-b", now.plusMinutes(3)), "an expired claim can be taken over");
        assertEquals("instance-b", repository.findById(id).orElseThrow().getClaimedBy());
    }

    @Test
    void theSenderSkipsRowsHeldByAnotherInstanceAndReleasesItsOwnClaims() {
        Long id = pendingEmail();
        assertNotEquals("other-instance", sender.instanceId());
        assertEquals(1, claim(id, "other-instance", UtcTime.now()));
        assertEquals(0, sender.sendPendingBatch(), "a row held by another instance is not sent");
        assertEquals(NotificationStatus.PENDING, repository.findById(id).orElseThrow().getStatus());

        jdbcTemplate.update("UPDATE notifications SET claimed_until = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(UtcTime.now().minusMinutes(1)), id);
        assertEquals(1, sender.sendPendingBatch());
        Notification sent = repository.findById(id).orElseThrow();
        assertEquals(NotificationStatus.SKIPPED, sent.getStatus(), "mail is disabled in tests");
        assertNull(sent.getClaimedBy());
        assertNull(sent.getClaimedUntil());
        assertEquals(0, sender.sendPendingBatch());
    }

    @Test
    void refereeOneTimeLinksAreRedactedAfterDelivery() {
        String token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd";
        notificationService.notify(NotificationRecipient.email("referee@example.test", null),
                NotificationTemplate.REFERENCE_REQUEST, Map.of("refereeName", "Pat", "candidateName", "Sam",
                        "relationship", "peer", "expiresAt", "2026-12-01T00:00:00Z",
                        "link", "http://localhost:5173/reference/" + token));
        assertEquals(1, sender.sendPendingBatch());
        Notification delivered = repository.findAllByRecipientEmailAndTemplateOrderByIdAsc("referee@example.test",
                NotificationTemplate.REFERENCE_REQUEST).get(0);
        assertEquals(NotificationStatus.SKIPPED, delivered.getStatus());
        assertFalse(delivered.getBody().contains(token), "the referee token is not kept at rest");
        assertTrue(delivered.getBody().contains("/reference/[removed-after-delivery]"), delivered.getBody());
    }

    @Test
    void failedSendsBackOffInsteadOfUsingUpEveryAttemptWithinSeconds() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));
        NotificationSender failing = mailSender(mailSender);
        notificationService.notify(NotificationRecipient.email("backoff@example.test", null),
                NotificationTemplate.APPLICATION_RECEIVED, Map.of("firstName", "B", "jobTitle", "Engineer"));
        Long id = repository.findAllByRecipientEmailAndTemplateOrderByIdAsc("backoff@example.test",
                NotificationTemplate.APPLICATION_RECEIVED).get(0).getId();

        LocalDateTime before = UtcTime.now();
        assertEquals(1, failing.sendPendingBatch());
        Notification afterFirst = repository.findById(id).orElseThrow();
        assertEquals(NotificationStatus.PENDING, afterFirst.getStatus());
        assertEquals(1, afterFirst.getAttempts());
        assertEquals("test-mailer", afterFirst.getClaimedBy(), "held until the retry time");
        assertNotNull(afterFirst.getClaimedUntil());
        long waitSeconds = Duration.between(before, afterFirst.getClaimedUntil()).getSeconds();
        assertTrue(waitSeconds >= 55 && waitSeconds <= 65, "first retry after about a minute, was " + waitSeconds + " s");
        assertEquals(0, failing.sendPendingBatch(), "not retried before the back-off has passed");
        assertEquals(0, sender.sendPendingBatch(), "nor by another instance");

        jdbcTemplate.update("UPDATE notifications SET claimed_until = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(UtcTime.now().minusSeconds(1)), id);
        assertEquals(1, failing.sendPendingBatch());
        Notification afterSecond = repository.findById(id).orElseThrow();
        assertEquals(2, afterSecond.getAttempts());
        long secondWait = Duration.between(UtcTime.now(), afterSecond.getClaimedUntil()).getSeconds();
        assertTrue(secondWait >= 110 && secondWait <= 125, "the delay doubles, was " + secondWait + " s");

        jdbcTemplate.update("UPDATE notifications SET attempts = 4, claimed_until = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(UtcTime.now().minusSeconds(1)), id);
        assertEquals(1, failing.sendPendingBatch());
        Notification dead = repository.findById(id).orElseThrow();
        assertEquals(NotificationStatus.FAILED, dead.getStatus());
        assertEquals(5, dead.getAttempts());
        assertNull(dead.getClaimedBy());
        assertNull(dead.getClaimedUntil());
    }

    @Test
    void anUnexpectedErrorCountsAsAnAttemptAndDoesNotStopTheBatch() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("broken mail session")).when(mailSender).send(any(SimpleMailMessage.class));
        NotificationSender failing = mailSender(mailSender);
        notificationService.notify(NotificationRecipient.email("one@example.test", null),
                NotificationTemplate.APPLICATION_RECEIVED, Map.of("firstName", "One"));
        notificationService.notify(NotificationRecipient.email("two@example.test", null),
                NotificationTemplate.APPLICATION_RECEIVED, Map.of("firstName", "Two"));

        assertEquals(2, failing.sendPendingBatch(), "the first failure does not abort the second message");
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        for (Notification row : repository.findAll()) {
            assertEquals(NotificationStatus.PENDING, row.getStatus());
            assertEquals(1, row.getAttempts());
        }
    }

    @Test
    void aSuccessfulSendIsRecordedAndReleasesTheClaim() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        notificationService.notify(NotificationRecipient.email("ok@example.test", null),
                NotificationTemplate.PASSWORD_RESET, Map.of("firstName", "Ok",
                        "link", "http://localhost:5173/reset-password?token=abcdefghijklmnop"));
        assertEquals(1, mailSender(mailSender).sendPendingBatch());
        Notification sent = repository.findAllByRecipientEmailAndTemplateOrderByIdAsc("ok@example.test",
                NotificationTemplate.PASSWORD_RESET).get(0);
        assertEquals(NotificationStatus.SENT, sent.getStatus());
        assertEquals(1, sent.getAttempts());
        assertNotNull(sent.getSentAt());
        assertNull(sent.getClaimedBy());
        assertFalse(sent.getBody().contains("abcdefghijklmnop"));
    }

    @SuppressWarnings("unchecked")
    private NotificationSender mailSender(JavaMailSender mailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        return new NotificationSender(repository, provider, transactionManager, false, true, false,
                "RecruitPro <no-reply@recruitpro.test>", 5, "test-mailer");
    }

    @Test
    void attachmentsAreStoredOnTheEmailRowOnly() {
        List<Notification> rows = notificationService.notify(
                new NotificationRecipient(null, "x@example.test", null), NotificationTemplate.INTERVIEW_CANCELLED,
                Map.of("firstName", "X"), new NotificationAttachment("cancel.ics", "text/calendar; method=CANCEL", "ICS"));
        assertEquals(1, rows.size(), "no inbox copy without a user id");
        Notification email = repository.findById(rows.get(0).getId()).orElseThrow();
        assertEquals(NotificationChannel.EMAIL, email.getChannel());
        assertEquals("cancel.ics", email.getAttachmentName());
        assertEquals("ICS", email.getAttachmentContent());
    }
}
