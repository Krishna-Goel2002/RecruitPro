package com.recruitpro.auth.mfa;

import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired OrganizationRepository organizations;
    @Autowired PasswordEncoder passwordEncoder;

    Organization organization;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        organization = fixtures.organization("No Key Org");
        fixtures.staff(organization, "hr@nokey.test", Role.HR_MANAGER);
        fixtures.staff(organization, "admin@nokey.test", Role.ADMIN);
    }

    @Test
    void staffSignInIsRefusedWhenTheOrganisationRequiresMfaButNoKeyIsConfigured() throws Exception {
        String password = "SecurePassword123!";
        fixtures.staff(organization, "staff@nokey.test", Role.RECRUITER, passwordEncoder.encode(password), true);
        String body = "{\"email\":\"staff@nokey.test\",\"password\":\"" + password + "\"}";
        mvc.perform(post("/api/v1/auth/login").header("X-RecruitPro-Portal", "staff")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // Required earlier (with a key), then the key went missing: fail closed instead of skipping the second factor.
        Organization requiring = organizations.findById(organization.getId()).orElseThrow();
        requiring.setRequireStaffMfa(true);
        organizations.save(requiring);
        mvc.perform(post("/api/v1/auth/login").header("X-RecruitPro-Portal", "staff")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", startsWith("MFA_NOT_CONFIGURED")))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
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
