package com.recruitpro.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.auth.entity.RefreshToken;
import com.recruitpro.auth.repository.RefreshTokenRepository;
import com.recruitpro.auth.service.TokenHashing;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.Role;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.recruitpro.support.AuthTestSupport.REFRESH_COOKIE;
import static com.recruitpro.support.AuthTestSupport.refreshCookie;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D11 / S2 / M6: refresh-token cookie rotation, 30-second grace window, reuse detection, logout. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenRotationIntegrationTest {

    private static final String PASSWORD = "SecurePassword123!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        users.save(User.builder().email("rotate@example.test").passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Rota").lastName("Tion").role(Role.CANDIDATE).enabled(true).build());
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void rotationKeepsTheFamilyAllowsAGraceWindowAndRevokesTheFamilyOnReuse() throws Exception {
        String first = refreshCookie(login());
        assertNotNull(first);
        assertFalse(first.isBlank());

        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(900))
                .andReturn();
        String second = refreshCookie(rotated);
        assertNotNull(second);
        assertNotEquals(first, second);

        RefreshToken firstRow = refreshTokens.findByTokenHash(TokenHashing.sha256(first)).orElseThrow();
        RefreshToken secondRow = refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow();
        assertEquals(firstRow.getFamilyId(), secondRow.getFamilyId());
        assertEquals("ROTATED", firstRow.getRevokedReason());
        assertNotNull(firstRow.getReplacedAt());
        assertNull(secondRow.getRevokedAt());

        // A second tab presenting the just-rotated token within 30 seconds gets an access token but no cookie.
        MvcResult grace = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        assertNull(refreshCookie(grace), "the grace response must not replace the cookie");
        assertNull(refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow().getRevokedAt());

        // Presented again after the grace window: treated as theft, the whole family is revoked.
        jdbcTemplate.update("UPDATE refresh_tokens SET replaced_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(31)), TokenHashing.sha256(first));
        MvcResult reused = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("", refreshCookie(reused), "a rejected refresh clears the cookie");

        RefreshToken revokedSecond = refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow();
        assertNotNull(revokedSecond.getRevokedAt());
        assertEquals("REUSE_DETECTED", revokedSecond.getRevokedReason());
        List<RefreshToken> family = refreshTokens.findAllByFamilyIdOrderByIdAsc(firstRow.getFamilyId());
        assertTrue(family.stream().allMatch(token -> token.getRevokedAt() != null));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc("REFRESH_TOKEN_REUSE_DETECTED").size());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        String first = refreshCookie(login());
        String second = refreshCookie(mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isOk()).andReturn());

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(REFRESH_COOKIE, second)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("", refreshCookie(logout));
        assertTrue(logout.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));

        assertEquals("LOGOUT", refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow().getRevokedReason());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, second)))
                .andExpect(status().isUnauthorized());
        // The rotated predecessor is inside its grace window, but the family has no active token any more.
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void separateSignInsAreSeparateFamilies() throws Exception {
        String a = refreshCookie(login());
        String b = refreshCookie(login());
        String familyA = refreshTokens.findByTokenHash(TokenHashing.sha256(a)).orElseThrow().getFamilyId();
        String familyB = refreshTokens.findByTokenHash(TokenHashing.sha256(b)).orElseThrow().getFamilyId();
        assertNotEquals(familyA, familyB);

        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(REFRESH_COOKIE, a))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, b))).andExpect(status().isOk());
    }

    @Test
    void aFamilyEndsAfterTheAbsoluteSessionLifetimeHoweverOftenItRotates() throws Exception {
        // R1-U2: rotation keeps the sign-in time; a family started 31 days ago (default limit 30 days) is refused.
        String first = refreshCookie(login());
        RefreshToken firstRow = refreshTokens.findByTokenHash(TokenHashing.sha256(first)).orElseThrow();
        assertNotNull(firstRow.getFamilyStartedAt());
        assertFalse(firstRow.getExpiresAt().isAfter(firstRow.getFamilyStartedAt().plusDays(30)));
        String second = refreshCookie(mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, first)))
                .andExpect(status().isOk()).andReturn());
        RefreshToken secondRow = refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow();
        assertEquals(firstRow.getFamilyStartedAt(), secondRow.getFamilyStartedAt(), "rotation keeps the sign-in time");

        // Sign-in 31 days ago; the token itself (sliding 14 days) has not expired.
        jdbcTemplate.update("UPDATE refresh_tokens SET family_started_at = ? WHERE family_id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(31)), firstRow.getFamilyId());
        MvcResult refused = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, second)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("", refreshCookie(refused));
        RefreshToken revoked = refreshTokens.findByTokenHash(TokenHashing.sha256(second)).orElseThrow();
        assertNotNull(revoked.getRevokedAt());
        assertEquals("SESSION_LIFETIME", revoked.getRevokedReason());

        // Near the absolute end a rotated token never outlives it.
        String fresh = refreshCookie(login());
        String freshFamily = refreshTokens.findByTokenHash(TokenHashing.sha256(fresh)).orElseThrow().getFamilyId();
        LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC).minusDays(29);
        jdbcTemplate.update("UPDATE refresh_tokens SET family_started_at = ? WHERE family_id = ?",
                Timestamp.valueOf(started), freshFamily);
        String rotated = refreshCookie(mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, fresh)))
                .andExpect(status().isOk()).andReturn());
        RefreshToken rotatedRow = refreshTokens.findByTokenHash(TokenHashing.sha256(rotated)).orElseThrow();
        assertFalse(rotatedRow.getExpiresAt().isAfter(started.plusDays(30).plusSeconds(1)),
                "the new token expires at the family's absolute end, not 14 days later");
    }

    @Test
    void refreshWithoutAnyTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, "not-a-real-token")))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rotate@example.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }
}
