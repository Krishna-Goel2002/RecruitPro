package com.recruitpro.admin;

import com.recruitpro.admin.audit.AdminAuditService;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.ai-service.poll-interval-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPortalIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository candidates;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired ResumeRepository resumes;
    @Autowired ApplicationRepository applications;
    @Autowired ResumeAnalysisRepository resumeAnalyses;
    @Autowired ResumeAnalysisFactRepository resumeFacts;
    @Autowired JobMatchRunRepository matchRuns;
    @Autowired ApplicationDecisionHistoryRepository history;
    @Autowired AdminAuditService auditService;

    private User primaryAdmin;
    private User secondaryAdmin;
    private User hrManagerUser;
    private User recruiterUser;
    private User candidateUser;
    private HRProfile hrProfile;
    private CandidateProfile candidateProfile;
    private Job testJob;
    private Application testApplication;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        primaryAdmin = createUser("admin1-" + suffix + "@example.test", Role.ADMIN, true);
        secondaryAdmin = createUser("admin2-" + suffix + "@example.test", Role.ADMIN, true);

        hrProfile = createHr("hrmgr-" + suffix + "@example.test", Role.HR_MANAGER, "Engineering", "Head of Talent", "EMP-" + suffix.substring(0, 8));
        hrManagerUser = hrProfile.getUser();

        HRProfile recruiterProfile = createHr("recruiter-" + suffix + "@example.test", Role.RECRUITER, "Engineering", "Technical Recruiter", "REC-" + suffix.substring(0, 8));
        recruiterUser = recruiterProfile.getUser();

        candidateProfile = createCandidate("cand-" + suffix + "@example.test");
        candidateUser = candidateProfile.getUser();

        testJob = createJob(hrProfile, "Software Engineer", "Engineering");
        testApplication = createApplication(candidateProfile, testJob);
    }

    @AfterEach
    void tearDown() {
        matchRuns.deleteAll();
        history.deleteAll();
        applications.deleteAll();
        resumeFacts.deleteAll();
        resumeAnalyses.deleteAll();
        resumes.deleteAll();
        candidates.deleteAll();
        jobs.deleteAll();
        hrProfiles.deleteAll();
        users.deleteAll();
    }

    @Test
    @Timeout(45)
    void unauthenticatedRequestsAreRejectedWithUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/hr-team"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/jobs"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"reason\":\"Filled\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Timeout(45)
    void nonAdminRolesAreForbiddenFromAllAdminEndpoints() throws Exception {
        for (String role : List.of("CANDIDATE", "RECRUITER", "HR_MANAGER", "INTERVIEWER")) {
            var principal = user("user-" + UUID.randomUUID() + "@example.test").roles(role);

            mvc.perform(get("/api/v1/admin/dashboard").with(principal))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/users").with(principal))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/role").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"RECRUITER\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/hr-team").with(principal))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/jobs").with(principal))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"reason\":\"Forbidden\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-logs").with(principal))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @Timeout(45)
    void adminCanFetchDashboardMetricsAndFunnel() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/dashboard").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.totalUsers", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.totalCandidates", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.totalHrUsers", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.totalJobs", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.totalApplications", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.applicationsByStatus.SUBMITTED", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.backendHealth", is("UP")))
                .andExpect(jsonPath("$.data.aiServiceHealth", notNullValue()));
    }

    @Test
    @Timeout(45)
    void adminCanSearchFilterAndPaginateUsers() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/users")
                        .param("page", "0")
                        .param("size", "10")
                        .param("role", "CANDIDATE")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.content[0].role", is("CANDIDATE")));

        mvc.perform(get("/api/v1/admin/users")
                        .param("search", candidateUser.getEmail())
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].email", is(candidateUser.getEmail())));
    }

    @Test
    @Timeout(45)
    void adminCanUpdateUserStatusWithProtectionForSelfAndLastAdmin() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        // Deactivate candidate
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(false)));

        assertFalse(users.findById(candidateUser.getId()).orElseThrow().isEnabled());

        // Re-activate candidate
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(true)));

        assertTrue(users.findById(candidateUser.getId()).orElseThrow().isEnabled());

        // Self-disable is prohibited
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot deactivate their own")));

        // Malformed / missing status is rejected
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Validation failed")));

        // Disable secondary admin (works because primary admin remains active)
        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled", is(false)));

        // If secondary admin is now disabled, attempting to disable primary admin fails as last active admin
        var secondaryPrincipal = user(secondaryAdmin.getEmail()).roles("ADMIN");
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/status")
                        .with(secondaryPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("last active administrator")));
    }

    @Test
    @Timeout(45)
    void adminCanUpdateUserRoleAndAutoProvisionProfile() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        // Promote candidate to RECRUITER -> HR profile is auto-provisioned
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"RECRUITER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("RECRUITER")));

        assertEquals(Role.RECRUITER, users.findById(candidateUser.getId()).orElseThrow().getRole());
        assertTrue(hrProfiles.findByUserId(candidateUser.getId()).isPresent());

        // Demote secondary admin to HR_MANAGER
        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("HR_MANAGER")));

        // Now primaryAdmin is the last admin -> Self-demotion is rejected
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CANDIDATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot demote their own account")));

        // Malformed / missing role request is rejected
        mvc.perform(patch("/api/v1/admin/users/" + candidateUser.getId() + "/role")
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

        // Scenario 1: Self-demotion when multiple active administrators exist is rejected
        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot demote their own account")));

        // Scenario 2: Demotion of another administrator when multiple active administrators exist succeeds
        mvc.perform(patch("/api/v1/admin/users/" + secondaryAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("HR_MANAGER")));

        assertEquals(Role.HR_MANAGER, users.findById(secondaryAdmin.getId()).orElseThrow().getRole());

        // Scenario 3: Attempted demotion of the last active administrator is rejected
        // Create an inactive admin account to act as principal (or demote caller)
        User inactiveAdmin = createUser("inactive-admin-" + UUID.randomUUID() + "@example.test", Role.ADMIN, false);
        var inactiveAdminPrincipal = user(inactiveAdmin.getEmail()).roles("ADMIN");

        mvc.perform(patch("/api/v1/admin/users/" + primaryAdmin.getId() + "/role")
                        .with(inactiveAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CANDIDATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("last remaining administrator")));

        // Scenario 4: Demotion of an inactive administrator when permitted by policy succeeds
        mvc.perform(patch("/api/v1/admin/users/" + inactiveAdmin.getId() + "/role")
                        .with(primaryAdminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CANDIDATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", is("CANDIDATE")));

        assertEquals(Role.CANDIDATE, users.findById(inactiveAdmin.getId()).orElseThrow().getRole());
    }

    @Test
    @Timeout(45)
    void adminCanFetchHrTeamDirectory() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        mvc.perform(get("/api/v1/admin/hr-team").with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.data[?(@.email == '%s')].department".formatted(hrManagerUser.getEmail()),
                        hasItem("Engineering")))
                .andExpect(jsonPath("$.data[?(@.email == '%s')].jobsCount".formatted(hrManagerUser.getEmail()),
                        hasItem(greaterThanOrEqualTo(1))));
    }

    @Test
    @Timeout(45)
    void adminCanListJobsAndPerformAdministrativeOverride() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        // List jobs
        mvc.perform(get("/api/v1/admin/jobs")
                        .param("status", "PUBLISHED")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.content[0].applicationCount", greaterThanOrEqualTo(1)));

        // Administrative override: Missing or blank reason rejected
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Administrative override: Unauthorized target status (e.g. PUBLISHED or DRAFT) rejected
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\",\"reason\":\"Reopen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Administrative override allows setting status to ON_HOLD, CLOSED, or CANCELLED")));

        // Valid override to ON_HOLD
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_HOLD\",\"reason\":\"Compliance audit under review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ON_HOLD")));

        assertEquals(JobStatus.ON_HOLD, jobs.findById(testJob.getId()).orElseThrow().getStatus());
    }

    @Test
    @Timeout(45)
    void adminCanViewAuditLogsAndAuditContainsRecordedActions() throws Exception {
        var adminPrincipal = user(primaryAdmin.getEmail()).roles("ADMIN");

        // Trigger an admin action
        mvc.perform(patch("/api/v1/admin/jobs/" + testJob.getId() + "/status")
                        .with(adminPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"Department restructuring\"}"))
                .andExpect(status().isOk());

        // Query audit logs
        mvc.perform(get("/api/v1/admin/audit-logs")
                        .param("page", "0")
                        .param("size", "20")
                        .with(adminPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.content[0].action", notNullValue()))
                .andExpect(jsonPath("$.data.content[0].actorEmail", notNullValue()));
    }

    private User createUser(String email, Role role, boolean enabled) {
        return users.save(User.builder()
                .email(email)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuvwxyz123456")
                .firstName("Test")
                .lastName("User")
                .role(role)
                .enabled(enabled)
                .build());
    }

    private CandidateProfile createCandidate(String email) {
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(createUser(email, Role.CANDIDATE, true));
        profile.setCity("Bangalore");
        profile.setYearsOfExperience(3);
        profile.setProfileSummary("Java, Spring Boot developer");
        return candidates.save(profile);
    }

    private HRProfile createHr(String email, Role role, String department, String designation, String employeeId) {
        HRProfile profile = new HRProfile();
        profile.setUser(createUser(email, role, true));
        profile.setDepartment(department);
        profile.setDesignation(designation);
        profile.setEmployeeId(employeeId);
        return hrProfiles.save(profile);
    }

    private Job createJob(HRProfile owner, String title, String department) {
        Job job = new Job();
        job.setCreatedBy(owner);
        job.setTitle(title);
        job.setDescription("Job description");
        job.setDepartment(department);
        job.setLocation("Bangalore");
        job.setJobType(JobType.FULL_TIME);
        job.setExperienceLevel(ExperienceLevel.MID);
        job.setMinimumSalary(new BigDecimal("1200000"));
        job.setMaximumSalary(new BigDecimal("2000000"));
        job.setVacancies(1);
        job.setStatus(JobStatus.PUBLISHED);
        job.setPublishedAt(LocalDateTime.now());
        job.setDeadline(LocalDateTime.now().plusDays(30));
        return jobs.save(job);
    }

    private Application createApplication(CandidateProfile applicant, Job targetJob) {
        Resume resume = new Resume();
        resume.setCandidate(applicant);
        resume.setStorageKey(UUID.randomUUID().toString());
        resume.setOriginalFilename("sample.pdf");
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(1024L);
        resume.setUploadedAt(LocalDateTime.now());
        resume = resumes.save(resume);

        Application app = new Application();
        app.setCandidate(applicant);
        app.setJob(targetJob);
        app.setResume(resume);
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setAppliedAt(LocalDateTime.now());
        return applications.save(app);
    }
}
