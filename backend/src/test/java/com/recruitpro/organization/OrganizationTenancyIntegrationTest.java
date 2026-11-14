package com.recruitpro.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.organization.repository.InvitationRepository;
import com.recruitpro.support.TestDatabase;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D3 / S5 / M1: organisation sign-up, invitations, and tenancy isolation. Uses real access tokens so the
 * JWT filter, the organisation claim and service-layer scoping are exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationTenancyIntegrationTest {

    private static final String PASSWORD = "TenantPassword123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired InvitationRepository invitations;
    @Autowired AuditEventRepository auditEvents;
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
    void signupCreatesAnOrganisationAdministratorWithAnHrProfile() throws Exception {
        MvcResult signup = signup("Northwind Hiring", "owner@northwind.test");
        JsonNode user = data(mapper, signup).path("user");
        assertEquals("ADMIN", user.path("role").asText());
        assertEquals("Northwind Hiring", user.path("organizationName").asText());
        assertFalse(user.path("emailVerified").asBoolean());
        assertNotNull(refreshCookie(signup));

        User admin = users.findByEmail("owner@northwind.test").orElseThrow();
        assertNotNull(admin.getOrganizationId());
        assertTrue(hrProfiles.findByUserId(admin.getId()).isPresent(), "admins get an HR profile so they can create jobs");
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(admin.getOrganizationId(), "ORGANIZATION_CREATED").size());

        String token = accessToken(mapper, signup);
        mvc.perform(get("/api/v1/admin/organization").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("Northwind Hiring")))
                .andExpect(jsonPath("$.data.slug", is("northwind-hiring")))
                .andExpect(jsonPath("$.data.blindReview", is(true)));
        mvc.perform(patch("/api/v1/admin/organization").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blindReview\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blindReview", is(false)))
                .andExpect(jsonPath("$.data.shareVerificationStatus", is(true)));
        mvc.perform(get("/api/v1/admin/audit-logs").param("action", "ORGANIZATION_UPDATED").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].actorEmail", is("owner@northwind.test")));

        // A second organisation with the same name gets a distinct slug; the same email is rejected.
        MvcResult second = signup("Northwind Hiring", "other-owner@northwind.test");
        String secondToken = accessToken(mapper, second);
        mvc.perform(get("/api/v1/admin/organization").header("Authorization", bearer(secondToken)))
                .andExpect(jsonPath("$.data.slug", is("northwind-hiring-2")));
        mvc.perform(post("/api/v1/organizations/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("Another Org", "owner@northwind.test")))
                .andExpect(status().isConflict());
    }

    @Test
    void invitationCanBePreviewedAcceptedOnceAndRevoked() throws Exception {
        String adminToken = accessToken(mapper, signup("Contoso Talent", "admin@contoso.test"));

        MvcResult invited = mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Manager@Contoso.test\",\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email", is("manager@contoso.test")))
                .andExpect(jsonPath("$.data.status", is("PENDING")))
                .andReturn();
        String inviteToken = tokenFrom(data(mapper, invited).path("inviteUrl").asText());

        mvc.perform(get("/api/v1/auth/invitations/{token}", inviteToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationName", is("Contoso Talent")))
                .andExpect(jsonPath("$.data.email", is("manager@contoso.test")))
                .andExpect(jsonPath("$.data.role", is("HR_MANAGER")));

        mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson(inviteToken, "weak")))
                .andExpect(status().isBadRequest());
        MvcResult accepted = mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson(inviteToken, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role", is("HR_MANAGER")))
                .andExpect(jsonPath("$.data.user.organizationName", is("Contoso Talent")))
                .andExpect(jsonPath("$.data.user.emailVerified", is(true)))
                .andReturn();
        assertNotNull(refreshCookie(accepted));
        User manager = users.findByEmail("manager@contoso.test").orElseThrow();
        assertTrue(hrProfiles.findByUserId(manager.getId()).isPresent());

        mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson(inviteToken, PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/invitations/{token}", inviteToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/invitations/{token}", "unknown-token")).andExpect(status().isNotFound());

        // Existing accounts cannot be invited; invitations are limited to staff roles.
        mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"manager@contoso.test\",\"role\":\"RECRUITER\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"cand@contoso.test\",\"role\":\"CANDIDATE\"}"))
                .andExpect(status().isBadRequest());

        MvcResult second = mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"recruiter@contoso.test\",\"role\":\"RECRUITER\"}"))
                .andExpect(status().isCreated()).andReturn();
        long secondId = data(mapper, second).path("id").asLong();
        String secondToken = tokenFrom(data(mapper, second).path("inviteUrl").asText());
        mvc.perform(delete("/api/v1/admin/invitations/{id}", secondId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson(secondToken, PASSWORD)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/admin/invitations").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].status", hasItem("ACCEPTED")))
                .andExpect(jsonPath("$.data[*].status", hasItem("REVOKED")))
                .andExpect(jsonPath("$.data[0].inviteUrl").doesNotExist());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.data.content[*].action", hasItem("INVITATION_CREATED")))
                .andExpect(jsonPath("$.data.content[*].action", hasItem("INVITATION_ACCEPTED")))
                .andExpect(jsonPath("$.data.content[*].action", hasItem("INVITATION_REVOKED")));

        // The invited manager is a member of the organisation and cannot use admin routes.
        String managerToken = accessToken(mapper, accepted);
        mvc.perform(get("/api/v1/admin/invitations").header("Authorization", bearer(managerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffOfOneOrganisationCannotSeeOrChangeAnotherOrganisationsData() throws Exception {
        String adminA = accessToken(mapper, signup("Alpha Recruiting", "admin@alpha.test"));
        String managerA = inviteAndAccept(adminA, "manager@alpha.test", "HR_MANAGER");
        String recruiterA = inviteAndAccept(adminA, "recruiter@alpha.test", "RECRUITER");
        String adminB = accessToken(mapper, signup("Beta Staffing", "admin@beta.test"));
        String managerB = inviteAndAccept(adminB, "manager@beta.test", "HR_MANAGER");

        long jobA = createJob(managerA, "Alpha Platform Engineer");
        long adminJobA = createJob(adminA, "Alpha Data Engineer");
        long jobB = createJob(managerB, "Beta Support Lead");
        publish(managerA, jobA);
        publish(managerB, jobB);

        // HR managers manage every job of their own organisation, including jobs created by others.
        mvc.perform(patch("/api/v1/jobs/{id}/status", adminJobA).header("Authorization", bearer(managerA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        // A foreign organisation's job behaves as missing for every staff operation.
        mvc.perform(get("/api/v1/jobs/{id}", jobB).header("Authorization", bearer(managerA))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/jobs/{id}", jobB).header("Authorization", bearer(managerA))
                        .contentType(MediaType.APPLICATION_JSON).content(jobJson("Hijacked")))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/jobs/{id}/status", jobB).header("Authorization", bearer(managerA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/jobs/{id}", jobB).header("Authorization", bearer(managerA))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobB).header("Authorization", bearer(managerA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SKILL\",\"description\":\"Java\",\"mandatory\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/hr/jobs/{id}/applicants", jobB).header("Authorization", bearer(managerA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/hr/jobs/{id}/applicants", jobB).header("Authorization", bearer(recruiterA))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/{id}", jobB).header("Authorization", bearer(adminA))).andExpect(status().isNotFound());

        // Lists only contain the caller's organisation.
        mvc.perform(get("/api/v1/jobs").header("Authorization", bearer(managerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(2)))
                .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Beta Support Lead"))));
        mvc.perform(get("/api/v1/jobs").header("Authorization", bearer(recruiterA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(2)));
        mvc.perform(get("/api/v1/jobs/{id}", jobA).header("Authorization", bearer(recruiterA))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/jobs/{id}", jobA).header("Authorization", bearer(recruiterA))
                        .contentType(MediaType.APPLICATION_JSON).content(jobJson("Recruiter edit")))
                .andExpect(status().isForbidden());

        // Administration is organisation-scoped too.
        User adminBUser = users.findByEmail("admin@beta.test").orElseThrow();
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(3)))
                .andExpect(jsonPath("$.data.content[*].email", not(hasItem("admin@beta.test"))));
        mvc.perform(patch("/api/v1/admin/users/{id}/status", adminBUser.getId()).header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/admin/users/{id}/role", adminBUser.getId()).header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/jobs").header("Authorization", bearer(adminA)))
                .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Beta Support Lead"))));
        mvc.perform(patch("/api/v1/admin/jobs/{id}/status", jobB).header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"reason\":\"Not ours\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/hr-team").header("Authorization", bearer(adminA)))
                .andExpect(jsonPath("$.data[*].email", not(hasItem("manager@beta.test"))));
        mvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalJobs", is(2)))
                .andExpect(jsonPath("$.data.health.database", is("UP")))
                .andExpect(jsonPath("$.data.health.queue.pending").exists());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", bearer(adminB)))
                .andExpect(jsonPath("$.data.content[*].actorEmail", everyItem(not(is("admin@alpha.test")))));

        // Candidates browse published jobs of every organisation.
        String candidate = accessToken(mapper, mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Cara\",\"lastName\":\"Ndidate\",\"email\":\"cara@example.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/v1/jobs").header("Authorization", bearer(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(2)))
                .andExpect(jsonPath("$.data.content[*].title", hasItem("Alpha Platform Engineer")))
                .andExpect(jsonPath("$.data.content[*].title", hasItem("Beta Support Lead")));
        mvc.perform(get("/api/v1/jobs/{id}", jobB).header("Authorization", bearer(candidate))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/jobs/{id}", adminJobA).header("Authorization", bearer(candidate))).andExpect(status().isNotFound());
    }

    @Test
    void interviewersHaveNoJobAccessYet() throws Exception {
        String admin = accessToken(mapper, signup("Gamma Group", "admin@gamma.test"));
        String interviewer = inviteAndAccept(admin, "interviewer@gamma.test", "INTERVIEWER");
        mvc.perform(get("/api/v1/jobs").header("Authorization", bearer(interviewer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(interviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("INTERVIEWER")));
        User interviewerUser = users.findByEmail("interviewer@gamma.test").orElseThrow();
        assertFalse(hrProfiles.findByUserId(interviewerUser.getId()).isPresent());
        assertEquals(Role.INTERVIEWER, interviewerUser.getRole());
    }

    private MvcResult signup(String organizationName, String email) throws Exception {
        return mvc.perform(post("/api/v1/organizations/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(organizationName, email)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String signupJson(String organizationName, String email) {
        return "{\"organizationName\":\"" + organizationName + "\",\"firstName\":\"Org\",\"lastName\":\"Owner\","
                + "\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private String acceptJson(String token, String password) {
        return "{\"token\":\"" + token + "\",\"firstName\":\"Invited\",\"lastName\":\"Member\",\"password\":\"" + password + "\"}";
    }

    private String inviteAndAccept(String adminToken, String email, String role) throws Exception {
        MvcResult invited = mvc.perform(post("/api/v1/admin/invitations").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = tokenFrom(data(mapper, invited).path("inviteUrl").asText());
        return accessToken(mapper, mvc.perform(post("/api/v1/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson(token, PASSWORD)))
                .andExpect(status().isOk()).andReturn());
    }

    private long createJob(String token, String title) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/jobs").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(jobJson(title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andReturn();
        return data(mapper, created).path("id").asLong();
    }

    private void publish(String token, long jobId) throws Exception {
        mvc.perform(patch("/api/v1/jobs/{id}/status", jobId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());
    }

    private String jobJson(String title) {
        return "{\"title\":\"" + title + "\",\"description\":\"Build things\",\"department\":\"Engineering\","
                + "\"location\":\"Remote\",\"jobType\":\"FULL_TIME\",\"experienceLevel\":\"MID\",\"minimumSalary\":50000,"
                + "\"maximumSalary\":90000,\"vacancies\":1}";
    }

    private static String tokenFrom(String inviteUrl) {
        int index = inviteUrl.indexOf("token=");
        assertTrue(index > 0, "inviteUrl must be exposed in the test profile: " + inviteUrl);
        return inviteUrl.substring(index + "token=".length());
    }
}
