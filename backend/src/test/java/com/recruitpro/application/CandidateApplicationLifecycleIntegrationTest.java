package com.recruitpro.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for the candidate application lifecycle.
 *
 * <p>Verifies resume ownership enforcement, application submission, detail and list queries,
 * lifecycle transitions (withdrawal, review decisions), duplicate prevention, invalid input rejection,
 * and role-based authorization.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateApplicationLifecycleIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateProfileRepository candidateProfileRepository;
    @Autowired private HRProfileRepository hrProfileRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private ResumeRepository resumeRepository;
    @Autowired private ResumeAnalysisRepository resumeAnalysisRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ApplicationDecisionHistoryRepository decisionHistoryRepository;

    private String candidateEmail;
    private String otherCandidateEmail;
    private CandidateProfile candidate;
    private CandidateProfile otherCandidate;
    private Resume candidateResume;
    private Resume otherResume;
    private Job publishedJobNoDeadline;
    private Job publishedJobWithDeadline;
    private Job draftJob;
    private Job expiredJob;
    private HRProfile hrProfile;

    @BeforeEach
    void setUp() {
        cleanupAll();
        String suffix = UUID.randomUUID().toString();
        candidateEmail = "candidate-" + suffix + "@example.test";
        otherCandidateEmail = "other-" + suffix + "@example.test";

        candidate = createCandidate(candidateEmail, "Candidate", "One");
        otherCandidate = createCandidate(otherCandidateEmail, "Candidate", "Two");

        User hrUser = createUser("hr-" + suffix + "@example.test", Role.HR_MANAGER, "HR", "Manager");
        hrProfile = new HRProfile();
        hrProfile.setUser(hrUser);
        hrProfile = hrProfileRepository.save(hrProfile);

        // Resume for candidate - simulating verified checkpoint (PENDING analysis, dedicated storage)
        candidateResume = createResume(candidate, "recruitpro-resume-integration.pdf", ResumeAnalysisStatus.PENDING);
        otherResume = createResume(otherCandidate, "other-candidate-resume.pdf", ResumeAnalysisStatus.COMPLETED);

        // Jobs in various states
        publishedJobNoDeadline = createJob("Backend Engineer - No Deadline " + suffix, JobStatus.PUBLISHED, null);
        publishedJobWithDeadline = createJob("Fullstack Dev - Open Deadline " + suffix, JobStatus.PUBLISHED, LocalDateTime.now().plusDays(30));
        draftJob = createJob("Draft Role " + suffix, JobStatus.DRAFT, null);
        expiredJob = createJob("Expired Role " + suffix, JobStatus.PUBLISHED, LocalDateTime.now().minusDays(5));
    }

    @AfterEach
    void tearDown() {
        cleanupAll();
    }

    private void cleanupAll() {
        decisionHistoryRepository.deleteAll();
        applicationRepository.deleteAll();
        resumeAnalysisRepository.deleteAll();
        resumeRepository.deleteAll();
        jobRepository.deleteAll();
        hrProfileRepository.deleteAll();
        candidateProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Complete happy path: submit application, verify details and list, withdraw, and block re-withdrawal")
    void completeApplicationLifecycleHappyPath() throws Exception {
        var auth = candidateAuth(candidateEmail);

        // 1. Submit application to published job with owned resume and short cover letter
        String payload = String.format("{\"resumeId\":%d,\"coverLetter\":\"  Passionate about backend distributed systems.  \"}",
                candidateResume.getId());

        String responseBody = mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Application submitted")))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.jobId", is(publishedJobNoDeadline.getId().intValue())))
                .andExpect(jsonPath("$.data.jobTitle", is(publishedJobNoDeadline.getTitle())))
                .andExpect(jsonPath("$.data.resumeId", is(candidateResume.getId().intValue())))
                .andExpect(jsonPath("$.data.resumeFilename", is("recruitpro-resume-integration.pdf")))
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.coverLetter", is("Passionate about backend distributed systems.")))
                .andExpect(jsonPath("$.data.appliedAt", notNullValue()))
                .andExpect(jsonPath("$.data.updatedAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        JsonNode jsonNode = mapper.readTree(responseBody);
        long applicationId = jsonNode.path("data").path("id").asLong();
        assertTrue(applicationId > 0);

        // 2. Retrieve application by ID (detail endpoint)
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId)
                        .with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.id", is((int) applicationId)))
                .andExpect(jsonPath("$.data.jobId", is(publishedJobNoDeadline.getId().intValue())))
                .andExpect(jsonPath("$.data.resumeId", is(candidateResume.getId().intValue())))
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")));

        // 3. Retrieve application in candidate's application list
        mvc.perform(get("/api/v1/candidates/me/applications")
                        .with(auth)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].id", is((int) applicationId)))
                .andExpect(jsonPath("$.data.content[0].status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.content[0].jobTitle", is(publishedJobNoDeadline.getTitle())));

        // 4. Withdraw application
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", applicationId)
                        .with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Application withdrawn")))
                .andExpect(jsonPath("$.data.id", is((int) applicationId)))
                .andExpect(jsonPath("$.data.status", is("WITHDRAWN")))
                .andExpect(jsonPath("$.data.updatedAt", notNullValue()));

        // 5. Confirm detail view shows WITHDRAWN status
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId)
                        .with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("WITHDRAWN")));

        // 6. Repeated withdrawal must fail with 400 Bad Request
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", applicationId)
                        .with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("Only submitted applications can be withdrawn")));
    }

    @Test
    @DisplayName("Enforce resume ownership: cannot apply with another candidate's resume")
    void cannotApplyWithAnotherCandidatesResume() throws Exception {
        var auth = candidateAuth(candidateEmail);

        // Attempt to apply using other candidate's resume
        String payload = String.format("{\"resumeId\":%d,\"coverLetter\":\"Attempting unauthorized resume\"}",
                otherResume.getId());

        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("Resume not found")));
    }

    @Test
    @DisplayName("Prevent duplicate applications to the same job")
    void preventDuplicateApplicationsToSameJob() throws Exception {
        var auth = candidateAuth(candidateEmail);
        String payload = String.format("{\"resumeId\":%d}", candidateResume.getId());

        // First application succeeds
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobWithDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")));

        // Duplicate submission returns 409 Conflict
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobWithDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("You have already applied to this job")));
    }

    @Test
    @DisplayName("Reject application to non-existent job ID with 404 Not Found")
    void rejectApplicationToNonExistentJob() throws Exception {
        var auth = candidateAuth(candidateEmail);
        String payload = String.format("{\"resumeId\":%d}", candidateResume.getId());

        mvc.perform(post("/api/v1/jobs/{jobId}/applications", 999999L)
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("Job not found")));
    }

    @Test
    @DisplayName("Reject application with missing or non-existent resume ID")
    void rejectApplicationWithMissingOrNonExistentResume() throws Exception {
        var auth = candidateAuth(candidateEmail);

        // Missing body
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("An uploaded resume is required to apply")));

        // Null resumeId
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":null}"))
                .andExpect(status().isBadRequest());

        // Non-existent resume ID
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("Resume not found")));
    }

    @Test
    @DisplayName("Reject application to draft or expired jobs with 400 Bad Request")
    void rejectApplicationToDraftOrExpiredJobs() throws Exception {
        var auth = candidateAuth(candidateEmail);
        String payload = String.format("{\"resumeId\":%d}", candidateResume.getId());

        // Draft job
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", draftJob.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Applications are accepted only for published jobs with an open deadline")));

        // Expired job
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", expiredJob.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Applications are accepted only for published jobs with an open deadline")));
    }

    @Test
    @DisplayName("Enforce isolation: other candidate cannot access or withdraw application")
    void enforceCrossCandidateAccessIsolation() throws Exception {
        var candidateAuth = candidateAuth(candidateEmail);
        var otherAuth = candidateAuth(otherCandidateEmail);

        // Candidate submits application
        String payload = String.format("{\"resumeId\":%d}", candidateResume.getId());
        String response = mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(candidateAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long appId = mapper.readTree(response).path("data").path("id").asLong();

        // Other candidate tries to get details -> 404
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", appId)
                        .with(otherAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("Application not found")));

        // Other candidate tries to withdraw -> 404
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId)
                        .with(otherAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("Application not found")));

        // Other candidate's list does not contain candidate's application
        mvc.perform(get("/api/v1/candidates/me/applications")
                        .with(otherAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(0)));
    }

    @Test
    @DisplayName("Enforce role authorization: non-candidate roles and anonymous users cannot access candidate application endpoints")
    void enforceRoleAuthorizationOnApplicationEndpoints() throws Exception {
        // Anonymous access rejected with 401
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":1}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/candidates/me/applications"))
                .andExpect(status().isUnauthorized());

        // Non-candidate roles rejected with 403 Forbidden
        for (Role role : new Role[]{Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER}) {
            var auth = SecurityMockMvcRequestPostProcessors.user("staff-" + role.name() + "@example.test").roles(role.name());

            mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                            .with(auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resumeId\":1}"))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/api/v1/candidates/me/applications")
                            .with(auth))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/api/v1/candidates/me/applications/1")
                            .with(auth))
                    .andExpect(status().isForbidden());

            mvc.perform(patch("/api/v1/candidates/me/applications/1/withdraw")
                            .with(auth))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Reject invalid state transitions: direct PATCH and withdrawing non-SUBMITTED applications")
    void rejectInvalidStateTransitions() throws Exception {
        var auth = candidateAuth(candidateEmail);

        // Submit application
        String payload = String.format("{\"resumeId\":%d}", candidateResume.getId());
        String response = mvc.perform(post("/api/v1/jobs/{jobId}/applications", publishedJobNoDeadline.getId())
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long appId = mapper.readTree(response).path("data").path("id").asLong();

        // 1. Direct PATCH on /applications/{id} is not supported (405 Method Not Allowed)
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}", appId)
                        .with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"WITHDRAWN\"}"))
                .andExpect(status().isMethodNotAllowed());

        // 2. Simulate HR shortlisting the application
        Application app = applicationRepository.findById(appId).orElseThrow();
        app.setStatus(ApplicationStatus.SHORTLISTED);
        app.setReviewComment("Candidate has strong qualifications");
        applicationRepository.saveAndFlush(app);

        // Candidate attempts to withdraw a SHORTLISTED application -> 400 Bad Request
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId)
                        .with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only submitted applications can be withdrawn")));

        // 3. Simulate HR rejecting the application
        app.setStatus(ApplicationStatus.REJECTED);
        app.setReviewComment("Position filled");
        applicationRepository.saveAndFlush(app);

        // Candidate attempts to withdraw a REJECTED application -> 400 Bad Request
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId)
                        .with(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only submitted applications can be withdrawn")));
    }

    // ---- Helper Methods ----

    private CandidateProfile createCandidate(String email, String first, String last) {
        User user = createUser(email, Role.CANDIDATE, first, last);
        CandidateProfile p = new CandidateProfile();
        p.setUser(user);
        return candidateProfileRepository.save(p);
    }

    private User createUser(String email, Role role, String first, String last) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash("$2a$10$dummyHashForCandidateApplicationTest")
                .firstName(first)
                .lastName(last)
                .role(role)
                .enabled(true)
                .build());
    }

    private Resume createResume(CandidateProfile profile, String filename, ResumeAnalysisStatus analysisStatus) {
        Resume resume = new Resume();
        resume.setCandidate(profile);
        resume.setStorageKey(UUID.randomUUID().toString());
        resume.setOriginalFilename(filename);
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(2048L);
        resume.setUploadedAt(LocalDateTime.now());
        resume = resumeRepository.save(resume);

        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResume(resume);
        analysis.setStatus(analysisStatus);
        resumeAnalysisRepository.save(analysis);

        return resume;
    }

    private Job createJob(String title, JobStatus status, LocalDateTime deadline) {
        Job job = new Job();
        job.setCreatedBy(hrProfile);
        job.setTitle(title);
        job.setDescription("Job description for " + title);
        job.setDepartment("Engineering");
        job.setLocation("Bengaluru, India");
        job.setJobType(JobType.FULL_TIME);
        job.setExperienceLevel(ExperienceLevel.SENIOR);
        job.setMinimumSalary(new BigDecimal("1200000.00"));
        job.setMaximumSalary(new BigDecimal("2000000.00"));
        job.setVacancies(2);
        job.setStatus(status);
        if (status == JobStatus.PUBLISHED) {
            job.setPublishedAt(LocalDateTime.now().minusDays(1));
        }
        job.setDeadline(deadline);
        return jobRepository.save(job);
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidateAuth(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }
}
