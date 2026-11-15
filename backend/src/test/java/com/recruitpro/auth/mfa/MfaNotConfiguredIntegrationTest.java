package com.recruitpro.auth.mfa;

import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without app.encryption.key MFA answers 503 MFA_NOT_CONFIGURED and cannot be required; SSO lists no providers. */
@SpringBootTest(properties = "app.encryption.key=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaNotConfiguredIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("No Key Org");
        fixtures.staff(organization, "hr@nokey.test", Role.HR_MANAGER);
        fixtures.staff(organization, "admin@nokey.test", Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void mfaReportsNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/me/mfa").with(user("hr@nokey.test").roles("HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available", is(false)));
        mvc.perform(post("/api/v1/me/mfa/enroll").with(user("hr@nokey.test").roles("HR_MANAGER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", startsWith("MFA_NOT_CONFIGURED")));
        mvc.perform(patch("/api/v1/admin/organization").with(user("admin@nokey.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireStaffMfa\":true}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/auth/sso/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(0)));
    }
}
