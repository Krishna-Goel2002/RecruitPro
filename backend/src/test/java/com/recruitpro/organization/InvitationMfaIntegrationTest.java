package com.recruitpro.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.recruitpro.support.AuthTestSupport.accessToken;
import static com.recruitpro.support.AuthTestSupport.bearer;
import static com.recruitpro.support.AuthTestSupport.data;
import static com.recruitpro.support.AuthTestSupport.refreshCookie;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug hunt R1: accepting an invitation into an organisation that requires staff MFA must stop at the enrolment
 * challenge like {@code /auth/login}; it used to hand out a full session (access token and refresh cookie) without
 * a second factor. Same context configuration as {@code MfaIntegrationTest} (encryption key set).
 */
@SpringBootTest(properties = "app.encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationMfaIntegrationTest {

    private static final String PASSWORD = "TenantPassword123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void acceptingAnInvitationIntoAnMfaOrganisationReturnsTheEnrolmentChallenge() throws Exception {
        String adminToken = accessToken(mapper, mvc.perform(post("/api/v1/organizations/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Secure Hiring\",\"firstName\":\"Org\",\"lastName\":\"Owner\","
                                + "\"email\":\"owner@secure.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(patch("/api/v1/admin/organization").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireStaffMfa\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requireStaffMfa", is(true)));

        MvcResult invited = mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"manager@secure.test\",\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isCreated()).andReturn();
        String inviteUrl = data(mapper, invited).path("inviteUrl").asText();
        String inviteToken = inviteUrl.substring(inviteUrl.indexOf("token=") + "token=".length());

        MvcResult accepted = mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + inviteToken + "\",\"firstName\":\"Mia\",\"lastName\":\"Manager\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaRequired", is(true)))
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired", is(true)))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn();
        assertNull(refreshCookie(accepted), "no session cookie before the second factor is enrolled");
        assertTrue(users.findByEmail("manager@secure.test").isPresent(), "the account itself is created");

        JsonNode challenge = data(mapper, accepted);
        String mfaToken = challenge.path("mfaToken").asText();
        assertFalse(mfaToken.isBlank());
        mvc.perform(post("/api/v1/me/mfa/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isNotEmpty());
    }
}
