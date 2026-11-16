package com.recruitpro.security.userdetails;

import com.recruitpro.security.jwt.JwtService;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-U3: an access token issued in the same second just before a password change must stop working; the standard
 * {@code iat} has whole seconds only, so tokens carry {@code iat_ms}.
 */
class PasswordChangePrecisionTest {

    private static final LocalDateTime CHANGED = LocalDateTime.parse("2026-11-16T10:00:00.501");

    @Test
    void aTokenIssuedOneMillisecondBeforeTheChangeInTheSameSecondIsRefused() {
        User user = user();
        long changedMillis = CHANGED.toInstant(ZoneOffset.UTC).toEpochMilli();
        Date sameSecond = Date.from(CHANGED.withNano(0).toInstant(ZoneOffset.UTC));

        assertFalse(CustomUserDetailsService.issuedAfterPasswordChange(user, sameSecond, changedMillis - 1),
                "issued 1 ms before the change");
        assertTrue(CustomUserDetailsService.issuedAfterPasswordChange(user, sameSecond, changedMillis),
                "the session created in the same millisecond as the change stays valid");
        assertTrue(CustomUserDetailsService.issuedAfterPasswordChange(user, sameSecond, changedMillis + 1));
        // Tokens without the claim (issued before the fix) keep the old second precision.
        assertTrue(CustomUserDetailsService.issuedAfterPasswordChange(user, sameSecond, null));
        assertFalse(CustomUserDetailsService.issuedAfterPasswordChange(user,
                Date.from(CHANGED.minusSeconds(1).toInstant(ZoneOffset.UTC)), null));
    }

    @Test
    void accessTokensCarryTheMillisecondIssueTime() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", "test-signing-key-for-recruitpro-tests-minimum-32-bytes");
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 900_000L);
        ReflectionTestUtils.setField(jwtService, "issuer", "recruitment-platform");
        long before = System.currentTimeMillis();
        User user = user();
        user.setId(7L);
        Claims claims = jwtService.parse(jwtService.generateAccessToken(user)).orElseThrow();
        Long issuedAtMillis = JwtService.issuedAtMillisClaim(claims);
        assertNotNull(issuedAtMillis);
        assertTrue(issuedAtMillis >= before && issuedAtMillis <= System.currentTimeMillis());
        assertEquals(issuedAtMillis / 1000, claims.getIssuedAt().getTime() / 1000);
    }

    private static User user() {
        User user = User.builder().email("precise@example.test").passwordHash("x").firstName("Pre").lastName("Cise")
                .role(Role.HR_MANAGER).enabled(true).build();
        user.setPasswordChangedAt(CHANGED);
        return user;
    }
}
