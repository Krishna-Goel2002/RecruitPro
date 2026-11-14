package com.recruitpro.admin;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPortalIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired UserRepository users;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired ResumeRepository resumes;
    @Autowired ApplicationRepository applications;
    @Autowired AuditEventRepository auditEvents;

    private Organization organization;
    private User primaryAdmin;
    private User secondaryAdmin;
    private User hrManagerUser;
    private User recruiterUser;
    private User interviewerUser;
    private User candidateUser;
    private Job testJob;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = fixtures.organization("Admin Portal Org");
        primaryAdmin = fixtures.staff(organization, "admin1-" + suffix + "@example.test", Role.ADMIN);
        secondaryAdmin = fixtures.staff(organization, "admin2-" + suffix + "@example.test", Role.ADMIN);

        hrManagerUser = fixtures.staff(organization, "hrmgr-" + suffix + "@example.test", Role.HR_MANAGER);
        HRProfile hrProfile = new HRProfile();
        hrProfile.setUser(hrManagerUser);
        hrProfile.setDepartment("Engineering");
        hrProfile.setDesignation("Head of Talent");
        hrProfile.setEmployeeId("EMP-" + suffix);
        hrProfile = hrProfiles.save(hrProfile);

        recruiterUser = fixtures.hrProfile(fixtures.staff(organization, "recruiter-" + suffix + "@example.test", Role.RECRUITER), "Engineering").getUser();
        interviewerUser = fixtures.staff(organization, "interviewer-" + suffix + "@example.test", Role.INTERVIEWER);

        CandidateProfile candidateProfile = fixtures.candidate("cand-" + suffix + "@example.test");
        candidateUser = candidateProfile.getUser();

        testJob = fixtures.job(hrProfile, "Software Engineer", JobStatus.PUBLISHED);
        createApplication(candidateProfile, testJob);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    @Timeout(45)
    void unauthenticatedRequestsAreRejectedWithUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/hr-team")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"reason\":\"Filled\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/organization")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/invitations")).andExpect(status().isUnauthorized());
    }

    @Test
    @Timeout(45)
    void nonAdminRolesAreForbiddenFromAllAdminEndpoints() throws Exception {
        for (String role : List.of("CANDIDATE", "RECRUITER", "HR_MANAGER", "INTERVIEWER")) {
            var principal = user("user-" + UUID.randomUUID() + "@example.test").roles(role);

            mvc.perform(get("/api/v1/admin/dashboard").with(principal)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/users").with(principal)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/role").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECRUITER\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/hr-team").with(principal)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/jobs").with(principal)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"reason\":\"Forbidden\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-logs").with(principal)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-export").with(principal)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/organization").with(principal)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/invitations").with(principal).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"x@example.test\",\"role\":\"RECRUITER\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/admin/invitations/1").with(principal)).andExpect(status().isForbidden());
        }
    }

    @Test
    @Timeout(45)
    void adminCanFetchOrganisationScopedDashboardMetricsAndHealth() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/dashboard").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.totalUsers", is(5)))
                .andExpect(jsonPath("$.data.totalCandidates", is(1)))
                .andExpect(jsonPath("$.data.totalHrUsers", is(2)))
                .andExpect(jsonPath("$.data.totalJobs", is(1)))
                .andExpect(jsonPath("$.data.totalApplications", is(1)))
                .andExpect(jsonPath("$.data.applicationsByStatus.SUBMITTED", is(1)))
                .andExpect(jsonPath("$.data.backendHealth", is("UP")))
                .andExpect(jsonPath("$.data.databaseHealth", is("UP")))
                .andExpect(jsonPath("$.data.aiServiceHealth", notNullValue()))
                .andExpect(jsonPath("$.data.health.database", is("UP")))
                .andExpect(jsonPath("$.data.health.queue.pending", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.health.queue.dead", greaterThanOrEqualTo(0)));
    }

    @Test
    @Timeout(45)
    void adminCanSearchFilterAndPaginateOrganisationStaff() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/users")
                        .param("page", "0")
                        .param("size", "10")
                        .param("role", "RECRUITER")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].role", is("RECRUITER")));

        mvc.perform(get("/api/v1/admin/users")
                        .param("search", recruiterUser.getEmail())
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].email", is(recruiterUser.getEmail())));

        // Candidates are global and never part of an organisation's staff list.
        mvc.perform(get("/api/v1/admin/users").param("search", candidateUser.getEmail()).with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));

        // L4: LIKE wildcards in the search term match literally.
        mvc.perform(get("/api/v1/admin/users").param("search", "%").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
        mvc.perform(get("/api/v1/admin/users").param("search", "_").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @Timeout(45)
    void adminCanUpdateUserStatusWithProtectionForSelfAndLastAdmin() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(false)));
        assertFalse(users.findById(recruiterUser.getId()).orElseThrow().isEnabled());

        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(true)));
        assertTrue(users.findById(recruiterUser.getId()).orElseThrow().isEnabled());

        // Candidates do not belong to the organisation: 404.
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot deactivate their own")));

        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Validation failed")));

        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(false)));

        var secondaryPrincipal = user(secondaryAdmin.getEmail()).roles("ADMIN");
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/status")
                        .with(secondaryPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("last active administrator")));

        assertEquals(3, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(), "USER_STATUS_CHANGED").size());
    }

    @Test
    @Timeout(45)
    void adminCanUpdateStaffRolesAndProfilesAreProvisioned() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        assertTrue(hrProfiles.findByUserId(interviewerUser.getId()).isEmpty());
        mvc.perform(patch("/api/v1/admin/users/" + interviewerUser.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("RECRUITER")));
        assertEquals(Role.RECRUITER, users.findById(interviewerUser.getId()).orElseThrow().getRole());
        assertTrue(hrProfiles.findByUserId(interviewerUser.getId()).isPresent());

        // Only staff roles can be assigned, and candidates are outside the organisation.
        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CANDIDATE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("HR_MANAGER")));
        assertTrue(hrProfiles.findByUserId(secondaryAdmin.getId()).isPresent());

        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot demote their own account")));

        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Validation failed")));
    }

    @Test
    @Timeout(45)
    void adminDemotionProtectionsEnforceSelfDemotionAndLastActiveAdminRules() throws Exception {
        var primaryAdminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot demote their own account")));

        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("HR_MANAGER")));
        assertEquals(Role.HR_MANAGER, users.findById(secondaryAdmin.getId()).orElseThrow().getRole());

        // The last enabled administrator cannot be demoted, even by another (disabled) administrator.
        User inactiveAdmin = fixtures.staff(organization, "inactive-admin-" + UUID.randomUUID() + "@example.test",
                Role.ADMIN, TestDataFactory.UNUSABLE_PASSWORD_HASH, false);
        var inactiveAdminPrincipal = user(inactiveAdmin.getEmail()).roles("ADMIN");
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(inactiveAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("last remaining administrator")));

        // A disabled administrator can be demoted while another administrator remains.
        mvc.perform(patch("/api/v1/admin/users/" + inactiveAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("RECRUITER")));
        assertEquals(Role.RECRUITER, users.findById(inactiveAdmin.getId()).orElseThrow().getRole());
    }

    @Test
    @Timeout(45)
    void adminCanFetchHrTeamDirectory() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/hr-team").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.email == '%s')].department".formatted(hrManagerUser.getEmail()),
                        hasItem("Engineering")))
                .andExpect(jsonPath("$.data[?(@.email == '%s')].jobsCount".formatted(hrManagerUser.getEmail()),
                        hasItem(greaterThanOrEqualTo(1))));
    }

    @Test
    @Timeout(45)
    void adminCanListJobsAndOverrideOnlyAlongTheTransitionTable() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/jobs")
                        .param("status", "PUBLISHED")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].applicationCount", is(1)));

        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\",\"reason\":\"Reopen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Administrative override allows setting status to ON_HOLD, CLOSED, or CANCELLED")));

        // H7: a published job cannot be cancelled by override.
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"reason\":\"Wrong posting\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot move a job from PUBLISHED to CANCELLED")));

        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_HOLD\",\"reason\":\"Compliance audit under review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ON_HOLD")));
        assertEquals(JobStatus.ON_HOLD, jobs.findById(testJob.getId()).orElseThrow().getStatus());

        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"Position filled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CLOSED")));

        // CLOSED is terminal for everyone, including the override.
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_HOLD\",\"reason\":\"Reconsider\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(JobStatus.CLOSED, jobs.findById(testJob.getId()).orElseThrow().getStatus());
    }

    @Test
    @Timeout(45)
    void auditLogsArePersistentFilterableAndExportable() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"Department restructuring\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/users/" + recruiterUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit-logs")
                        .param("page", "0")
                        .param("size", "20")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.totalElements", is(2)))
                .andExpect(jsonPath("$.data.content[0].action", is("USER_STATUS_CHANGED")))
                .andExpect(jsonPath("$.data.content[0].actorEmail", is(primaryAdmin.getEmail())))
                .andExpect(jsonPath("$.data.content[0].actorUserId", is(primaryAdmin.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].createdAt", endsWith("Z")));

        mvc.perform(get("/api/v1/admin/audit-logs")
                        .param("action", "ADMIN_JOB_STATUS_OVERRIDE")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].targetType", is("JOB")))
                .andExpect(jsonPath("$.data.content[0].targetId", is(String.valueOf(testJob.getId()))))
                .andExpect(jsonPath("$.data.content[0].details", containsString("Department restructuring")));

        mvc.perform(get("/api/v1/admin/audit-export").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(startsWith("id,createdAt,action,actorEmail")))
                .andExpect(content().string(containsString("ADMIN_JOB_STATUS_OVERRIDE")));
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(), "AUDIT_EXPORTED").size());

        mvc.perform(get("/api/v1/admin/audit-export").param("from", "yesterday").with(adminPrincipal))
                .andExpect(status().isBadRequest());
    }

    private Application createApplication(CandidateProfile applicant, Job targetJob) {
        Resume resume = new Resume();
        resume.setCandidate(applicant);
        resume.setStorageKey(UUID.randomUUID().toString());
        resume.setOriginalFilename("sample.pdf");
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(1024L);
        resume.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC));
        resume = resumes.save(resume);

        Application app = new Application();
        app.setCandidate(applicant);
        app.setJob(targetJob);
        app.setResume(resume);
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setAppliedAt(LocalDateTime.now(ZoneOffset.UTC));
        return applications.save(app);
    }
}
