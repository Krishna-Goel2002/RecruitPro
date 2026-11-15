package com.recruitpro.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.verification.client.AgentServiceClient;
import com.recruitpro.verification.client.GitHubOAuthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared context of the verification integration tests (one Spring context for all of them): H2, a test encryption
 * key, a configured GitHub OAuth app and agent token, and mocked agent-service and GitHub clients (no network).
 */
@SpringBootTest(properties = {
        "app.encryption.key=" + VerificationFixtures.TEST_ENCRYPTION_KEY,
        "app.verification.github.client-id=test-client-id",
        "app.verification.github.client-secret=test-client-secret",
        "app.verification.github.redirect-uri=http://localhost:8080/api/v1/verification/oauth/github/callback",
        "app.agent-service.service-token=test-agent-token",
        "app.agent-service.base-url=http://127.0.0.1:9"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class VerificationIntegrationTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected TestDataFactory fixtures;
    @Autowired protected VerificationFixtures verification;

    @MockitoBean protected AgentServiceClient agentClient;
    @MockitoBean protected GitHubOAuthClient gitHubClient;

    @BeforeEach
    void cleanBefore() {
        fixtures.cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        fixtures.cleanDatabase();
    }

    protected static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidate(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }

    protected static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

    protected JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
