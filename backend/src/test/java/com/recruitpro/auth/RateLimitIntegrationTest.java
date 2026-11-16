package com.recruitpro.auth;

import com.recruitpro.security.filter.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S1: per-IP sliding-window limits on public auth routes (enabled for this test only). */
@SpringBootTest(properties = "app.rate-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RateLimitFilter rateLimitFilter;

    @BeforeEach
    void resetCounters() {
        rateLimitFilter.reset();
    }

    @Test
    void loginAllowsTenAttemptsPerMinuteThenReturns429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@example.test\",\"password\":\"whatever-123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\",\"password\":\"whatever-123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("Too many requests")));
    }

    @Test
    void forgotPasswordAllowsFiveRequestsPerFifteenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@example.test\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void accountDeletionAllowsFiveAttemptsPerFifteenMinutes() throws Exception {
        // WP-BE-C: the password confirmation of DELETE /api/v1/candidates/me must not be guessable without limit.
        for (int i = 0; i < 5; i++) {
            mvc.perform(delete("/api/v1/candidates/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"guess-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/v1/candidates/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"guess-6\"}"))
                .andExpect(status().isTooManyRequests());
        // Other methods on the same path are not limited.
        for (int i = 0; i < 7; i++) {
            mvc.perform(get("/api/v1/candidates/me")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void passwordChangeAndMfaStepsAreLimited() throws Exception {
        // R1-H2: password-confirmed account changes and the MFA steps reachable with an mfaToken are limited per IP.
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"guess-" + i + "\",\"newPassword\":\"NewPassword123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"guess-11\",\"newPassword\":\"NewPassword123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/me/mfa/confirm").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"123456\",\"mfaToken\":\"forged\"}"))
                    .andExpect(result -> assertNotEquals(429, result.getResponse().getStatus()));
        }
        mvc.perform(post("/api/v1/me/mfa/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\",\"mfaToken\":\"forged\"}"))
                .andExpect(status().isTooManyRequests());
        // Integration pass (R1-U1): the recovery-code step-up takes a code or the password, so it is limited too.
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/me/mfa/recovery-codes").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"guess-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/me/mfa/recovery-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"guess-11\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Reading the account is not limited.
        for (int i = 0; i < 15; i++) {
            mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void percentEncodedPathsCountAgainstTheSameLimit() throws Exception {
        // R1-M2: MVC decodes /api/v1/candidates/m%65 to /api/v1/candidates/me, so the limiter must too.
        for (int i = 0; i < 5; i++) {
            mvc.perform(delete(URI.create("/api/v1/candidates/m%65")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"guess-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/v1/candidates/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"guess-6\"}"))
                .andExpect(status().isTooManyRequests());
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(URI.create("/api/v1/me/p%61ssword")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"guess-" + i + "\",\"newPassword\":\"NewPassword123\"}"));
        }
        mvc.perform(post(URI.create("/api/v1/me/p%61ssword")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"guess-11\",\"newPassword\":\"NewPassword123\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void limitsArePerRouteAndOtherRoutesAreNotLimited() throws Exception {
        for (int i = 0; i < 11; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.test\",\"password\":\"whatever-123\"}"));
        }
        // A different auth route has its own bucket.
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isOk());
        // Routes outside /api/v1/auth are not rate limited.
        for (int i = 0; i < 15; i++) {
            mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        }
    }
}
