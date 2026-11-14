package com.recruitpro.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationStatus;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationSender;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.recruitpro.support.AuthTestSupport.REFRESH_COOKIE;
import static com.recruitpro.support.AuthTestSupport.accessToken;
import static com.recruitpro.support.AuthTestSupport.bearer;
import static com.recruitpro.support.AuthTestSupport.refreshCookie;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email verification, password reset, password change, GET /me and the notification inbox (S4, D13). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowsIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_\\-]+)");
    private static final String EMAIL = "flows@example.test";
    private static final String PASSWORD = "FirstPassword123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService notificationService;
    @Autowired NotificationSender notificationSender;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void registrationSendsAVerificationLinkThatWorksOnce() throws Exception {
        MvcResult registered = register();
        String token = latestToken(NotificationTemplate.VERIFY_EMAIL);
        Notification email = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(EMAIL, NotificationTemplate.VERIFY_EMAIL).get(0);
        assertEquals(NotificationChannel.EMAIL, email.getChannel());
        assertEquals(NotificationStatus.PENDING, email.getStatus());
        assertTrue(email.getBody().contains("http://localhost:5173/verify-email?token="));

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(accessToken(mapper, registered))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email", is(EMAIL)))
                .andExpect(jsonPath("$.data.emailVerified", is(false)));

        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified", is(true)));
        assertNotNull(users.findByEmail(EMAIL).orElseThrow().getEmailVerifiedAt());

        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPasswordAlwaysSucceedsAndResetRevokesEverySession() throws Exception {
        MvcResult registered = register();
        String sessionCookie = refreshCookie(registered);

        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.test\"}"))
                .andExpect(status().isOk());
        assertTrue(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("unknown@example.test",
                NotificationTemplate.PASSWORD_RESET).isEmpty());

        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  FLOWS@example.test \"}"))
                .andExpect(status().isOk());
        String token = latestToken(NotificationTemplate.PASSWORD_RESET);

        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"SecondPassword456\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"ThirdPassword789\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, sessionCookie)))
                .andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isUnauthorized());
        login("SecondPassword456").andExpect(status().isOk());
        assertNotNull(users.findByEmail(EMAIL).orElseThrow().getPasswordChangedAt());
    }

    @Test
    void changePasswordRequiresTheCurrentPasswordAndRevokesOtherSessions() throws Exception {
        MvcResult registered = register();
        String access = accessToken(mapper, registered);
        String otherSession = refreshCookie(login(PASSWORD).andExpect(status().isOk()).andReturn());

        mvc.perform(post("/api/v1/me/password").header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-my-password-1\",\"newPassword\":\"ChangedPassword123\"}"))
                .andExpect(status().isBadRequest());

        MvcResult changed = mvc.perform(post("/api/v1/me/password").header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"ChangedPassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(accessToken(mapper, changed))))
                .andExpect(status().isOk());
        String newSession = refreshCookie(changed);
        assertNotNull(newSession);
        assertFalse(newSession.isBlank());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, otherSession)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, newSession)))
                .andExpect(status().isOk());
        login("ChangedPassword123").andExpect(status().isOk());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void inboxListsOnlyInAppNotificationsOfTheCurrentUserWithUtcTimestamps() throws Exception {
        MvcResult registered = register();
        String access = accessToken(mapper, registered);
        User user = users.findByEmail(EMAIL).orElseThrow();

        mvc.perform(get("/api/v1/me/notifications/unread-count").header("Authorization", bearer(access)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count", is(0)));

        List<Notification> created = notificationService.notify(NotificationRecipient.of(user),
                NotificationTemplate.APPLICATION_RECEIVED,
                Map.of("firstName", "Flo", "jobTitle", "Backend Engineer", "organizationName", "Acme Labs"));
        assertEquals(2, created.size(), "email outbox row plus inbox row");

        mvc.perform(get("/api/v1/me/notifications").header("Authorization", bearer(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].template", is("APPLICATION_RECEIVED")))
                .andExpect(jsonPath("$.data.content[0].subject", is("We received your application for Backend Engineer")))
                .andExpect(jsonPath("$.data.content[0].createdAt", endsWith("Z")));
        mvc.perform(get("/api/v1/me/notifications/unread-count").header("Authorization", bearer(access)))
                .andExpect(jsonPath("$.data.count", is(1)));

        Long inboxId = created.stream().filter(n -> n.getChannel() == NotificationChannel.IN_APP)
                .findFirst().orElseThrow().getId();
        mvc.perform(patch("/api/v1/me/notifications/{id}/read", inboxId).header("Authorization", bearer(access)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/notifications/unread-count").header("Authorization", bearer(access)))
                .andExpect(jsonPath("$.data.count", is(0)));
        mvc.perform(post("/api/v1/me/notifications/read-all").header("Authorization", bearer(access)))
                .andExpect(status().isOk());

        Long emailId = created.stream().filter(n -> n.getChannel() == NotificationChannel.EMAIL)
                .findFirst().orElseThrow().getId();
        mvc.perform(patch("/api/v1/me/notifications/{id}/read", emailId).header("Authorization", bearer(access)))
                .andExpect(status().isNotFound());
    }

    @Test
    void senderSkipsEmailWhenMailIsDisabledAndRemovesOneTimeTokens() throws Exception {
        register();
        int processed = notificationSender.sendPendingBatch();
        assertTrue(processed >= 1);
        Notification verification = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(EMAIL,
                NotificationTemplate.VERIFY_EMAIL).get(0);
        assertEquals(NotificationStatus.SKIPPED, verification.getStatus());
        assertNotNull(verification.getSentAt());
        assertFalse(TOKEN.matcher(verification.getBody()).find(), "the one-time token is removed after delivery");
    }

    private MvcResult register() throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Flo\",\"lastName\":\"Ws\",\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
    }

    private String latestToken(NotificationTemplate template) {
        List<Notification> sent = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(EMAIL, template);
        assertFalse(sent.isEmpty(), "expected a " + template + " notification");
        Matcher matcher = TOKEN.matcher(sent.get(sent.size() - 1).getBody());
        assertTrue(matcher.find(), "notification body must contain a token link");
        return matcher.group(1);
    }
}
