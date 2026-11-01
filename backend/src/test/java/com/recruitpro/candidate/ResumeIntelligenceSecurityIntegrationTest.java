package com.recruitpro.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.*;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.hr.entity.*;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.ai-service.poll-interval-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeIntelligenceSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository candidates;
    @Autowired ResumeRepository resumes;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired ApplicationRepository applications;

    private String candidateEmail;
    private String otherCandidateEmail;
    private String managerEmail;
    private CandidateProfile candidate;
    private CandidateProfile otherCandidate;
    private Job job;

    @BeforeEach
    void setUp() {
        cleanUp();
        String suffix = UUID.randomUUID().toString();
        candidateEmail = "phase6-candidate-" + suffix + "@example.test";
        otherCandidateEmail = "phase6-other-" + suffix + "@example.test";
        candidate = createCandidate(candidateEmail);
        otherCandidate = createCandidate(otherCandidateEmail);
        managerEmail = "phase6-manager-" + suffix + "@example.test";
        User manager = createUser(managerEmail, Role.HR_MANAGER);
        HRProfile hr = new HRProfile(); hr.setUser(manager); hrProfiles.save(hr);
        job = new Job(); job.setCreatedBy(hr); job.setTitle("Phase 6 Test Job"); job.setDescription("Backend role");
        job.setDepartment("Engineering"); job.setLocation("Remote"); job.setJobType(JobType.FULL_TIME);
        job.setExperienceLevel(ExperienceLevel.MID); job.setMinimumSalary(new BigDecimal("50000"));
        job.setMaximumSalary(new BigDecimal("90000")); job.setVacancies(1); job.setStatus(JobStatus.PUBLISHED);
        job.setPublishedAt(LocalDateTime.now()); job = jobs.save(job);
        JobRequirement requirement = new JobRequirement(); requirement.setJob(job); requirement.setType(RequirementType.SKILL);
        requirement.setDescription("Java and Spring Boot"); requirement.setMandatory(true); requirements.save(requirement);
    }

    @Test
    @Timeout(45)
    void candidateCanOnlyReadAndCorrectFactsForTheirOwnResumeAndRetryOnlyFailedAnalysis() throws Exception {
        long resumeId = uploadResume(candidateEmail);
        ResumeAnalysis analysis = analyses.findByResumeId(resumeId).orElseThrow();
        assertEquals(ResumeAnalysisStatus.PENDING, analysis.getStatus());

        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", resumeId).with(candidateAuth(candidateEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("PENDING")));
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", resumeId).with(candidateAuth(otherCandidateEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", 999999L).with(candidateAuth(candidateEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/retry", resumeId).with(candidateAuth(candidateEmail)))
                .andExpect(status().isBadRequest());

        analysis.setStatus(ResumeAnalysisStatus.COMPLETED); analysis.setParserVersion("rule-parser-test"); analyses.save(analysis);
        ResumeAnalysisFact fact = new ResumeAnalysisFact(); fact.setAnalysis(analysis); fact.setType(ResumeFactType.SKILL);
        fact.setExtractedValue("Java"); fact.setNormalizedValue("java"); fact.setEvidence("Java services");
        fact.setSourceSection("skills"); fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED); facts.save(fact);

        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}", resumeId, fact.getId())
                        .with(candidateAuth(candidateEmail)).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"Java 17\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value", is("Java 17")))
                .andExpect(jsonPath("$.data.extractedValue", is("Java")))
                .andExpect(jsonPath("$.data.reviewStatus", is("CORRECTED")));
        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}", resumeId, fact.getId())
                        .with(candidateAuth(otherCandidateEmail)).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"Other\"}"))
                .andExpect(status().isNotFound());

        analysis = analyses.findById(analysis.getId()).orElseThrow();
        analysis.setStatus(ResumeAnalysisStatus.FAILED); analysis.setErrorCode("AI_SERVICE_UNAVAILABLE"); analyses.save(analysis);
        mvc.perform(patch("/api/v1/candidates/me/resumes/{id}/analysis/retry", resumeId).with(candidateAuth(candidateEmail)))
                .andExpect(status().isOk());
        assertEquals(ResumeAnalysisStatus.PENDING, analyses.findById(analysis.getId()).orElseThrow().getStatus());
    }

    @Test
    @Timeout(45)
    void HRReadsOnlyAuthorizedJobApplicantsAndRecruitersCannotRetry() throws Exception {
        long resumeId = uploadResume(candidateEmail);
        Application application = new Application(); application.setCandidate(candidate); application.setJob(job);
        application.setResume(resumes.findById(resumeId).orElseThrow()); application.setStatus(ApplicationStatus.SUBMITTED);
        application.setAppliedAt(LocalDateTime.now()); applications.save(application);

        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).with(user("recruiter@example.test").roles("RECRUITER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].applicationId", is(application.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].analysisStatus", is("PENDING")))
                .andExpect(jsonPath("$.data.content[0].observedEvidenceCoverage").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].requirements.length()", is(0)))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(candidateEmail)));
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).with(candidateAuth(candidateEmail)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", 999999L).with(user(managerEmail).roles("HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/analysis/retry", job.getId(), application.getId())
                        .with(user("recruiter@example.test").roles("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/shortlist", job.getId(), application.getId())
                        .with(user("recruiter@example.test").roles("RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        ResumeAnalysis analysis = analyses.findByResumeId(resumeId).orElseThrow();
        analysis.setStatus(ResumeAnalysisStatus.FAILED); analysis.setErrorCode("AI_SERVICE_UNAVAILABLE"); analyses.save(analysis);
        mvc.perform(patch("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/analysis/retry", job.getId(), 999999L)
                        .with(user(managerEmail).roles("HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/analysis/retry", job.getId(), application.getId())
                        .with(user(managerEmail).roles("HR_MANAGER")))
                .andExpect(status().isOk());
        assertEquals(ResumeAnalysisStatus.PENDING, analyses.findById(analysis.getId()).orElseThrow().getStatus());
    }

    private long uploadResume(String email) throws Exception {
        byte[] pdf = "%PDF-1.7\nminimal test document\n%%EOF".getBytes();
        String json = mvc.perform(multipart("/api/v1/candidates/me/resumes")
                        .file(new MockMultipartFile("file", "private.pdf", "application/pdf", pdf))
                        .with(candidateAuth(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asLong();
    }

    private CandidateProfile createCandidate(String email) {
        CandidateProfile profile = new CandidateProfile(); profile.setUser(createUser(email, Role.CANDIDATE)); return candidates.save(profile);
    }

    private User createUser(String email, Role role) {
        return users.save(User.builder().email(email).passwordHash("test-only").firstName("Test").lastName("User").role(role).enabled(true).build());
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidateAuth(String email) {
        return user(email).roles("CANDIDATE");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        applications.deleteAll();
        facts.deleteAll();
        analyses.deleteAll();
        resumes.deleteAll();
        requirements.deleteAll();
        jobs.deleteAll();
        hrProfiles.deleteAll();
        candidates.deleteAll();
        users.deleteAll();
    }
}
