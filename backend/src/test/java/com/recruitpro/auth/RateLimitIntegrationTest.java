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

import static org.hamcrest.Matchers.containsString;
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
