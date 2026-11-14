package com.recruitpro.auth;

import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S1: five failed sign-ins lock the account for 15 minutes; a locked account gets the same 401. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginLockoutIntegrationTest {

    private static final String EMAIL = "lockout@example.test";
    private static final String PASSWORD = "SecurePassword123!";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        userId = users.save(User.builder().email(EMAIL).passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Lock").lastName("Out").role(Role.CANDIDATE).enabled(true).build()).getId();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheCorrectPassword() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            login("wrong-password-1").andExpect(status().isUnauthorized());
            assertEquals(attempt, users.findById(userId).orElseThrow().getFailedLoginCount());
        }
        login("wrong-password-1").andExpect(status().isUnauthorized());
        User locked = users.findById(userId).orElseThrow();
        assertNotNull(locked.getLockedUntil());
        assertTrue(locked.getLockedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(14)));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc("LOGIN_LOCKED").size());

        String wrongPasswordMessage = login("wrong-password-1").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String lockedMessage = login(PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertEquals(messageOf(wrongPasswordMessage), messageOf(lockedMessage),
                "a locked account must look exactly like a wrong password");
    }

    @Test
    void lockExpiresAndASuccessfulSignInResetsTheCounter() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            login("wrong-password-1").andExpect(status().isUnauthorized());
        }
        jdbcTemplate.update("UPDATE users SET locked_until = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)), userId);

        login(PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        User reset = users.findById(userId).orElseThrow();
        assertEquals(0, reset.getFailedLoginCount());
        assertNull(reset.getLockedUntil());
    }

    @Test
    void unknownEmailsAreNotCountedAndLookLikeWrongPasswords() throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@example.test\",\"password\":\"whatever-123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(0, users.findById(userId).orElseThrow().getFailedLoginCount());
        assertTrue(auditEvents.findAllByActionOrderByIdAsc("LOGIN_LOCKED").isEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
    }

    private static String messageOf(String json) {
        int start = json.indexOf("\"message\":\"");
        int end = json.indexOf('"', start + 11);
        return json.substring(start + 11, end);
    }
}
