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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
