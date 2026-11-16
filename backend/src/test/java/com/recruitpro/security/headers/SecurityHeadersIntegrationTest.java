package com.recruitpro.security.headers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security headers and the request id on API responses, including error responses; Prometheus is not public. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersIntegrationTest {

    @Autowired MockMvc mvc;

    private static void expectSecurityHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[A-Za-z0-9._-]{8,64}")));
    }

    @Test
    void publicApiResponsesCarryTheHeaders() throws Exception {
        expectSecurityHeaders(mvc.perform(get("/api/v1/health")).andExpect(status().isOk()));
    }

    @Test
    void unauthenticatedAndForbiddenResponsesCarryThemToo() throws Exception {
        expectSecurityHeaders(mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()));
        expectSecurityHeaders(mvc.perform(get("/api/v1/admin/organization").with(user("cand@x.test").roles("CANDIDATE")))
                .andExpect(status().isForbidden()));
    }

    @Test
    void anIncomingSafeRequestIdIsEchoed() throws Exception {
        mvc.perform(get("/api/v1/health").header("X-Request-Id", "trace-abc-12345"))
                .andExpect(header().string("X-Request-Id", "trace-abc-12345"));
    }

    @Test
    void percentEncodedApiPathsGetTheCspToo() throws Exception {
        // R1-M2: MVC serves /%61pi/v1/health as /api/v1/health, so the header filter must recognise it as an API path.
        mvc.perform(get(URI.create("/%61pi/v1/health")))
                .andExpect(header().string("Content-Security-Policy", ApiSecurityHeadersFilter.API_CSP));
    }

    @Test
    void prometheusNeedsAnAdministratorByDefault() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(user("hr@x.test").roles("HR_MANAGER"))).andExpect(status().isForbidden());
    }
}
