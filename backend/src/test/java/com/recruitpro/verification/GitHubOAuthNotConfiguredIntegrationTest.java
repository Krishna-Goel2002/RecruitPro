package com.recruitpro.verification;

import com.recruitpro.support.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without GITHUB_CLIENT_ID/SECRET (or without an encryption key) OAuth answers 503 OAUTH_NOT_CONFIGURED. */
@SpringBootTest(properties = {"app.verification.github.client-id=", "app.verification.github.client-secret=",
        "app.encryption.key="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitHubOAuthNotConfiguredIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        fixtures.candidate("noauth@cand.test");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void startAnswers503AndTheCallbackRedirectsWithTheCode() throws Exception {
        mvc.perform(get("/api/v1/candidates/me/verification/oauth/github/start")
                        .with(SecurityMockMvcRequestPostProcessors.user("noauth@cand.test").roles("CANDIDATE")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("OAUTH_NOT_CONFIGURED")));
        mvc.perform(get("/api/v1/verification/oauth/github/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=OAUTH_NOT_CONFIGURED"));
    }
}
