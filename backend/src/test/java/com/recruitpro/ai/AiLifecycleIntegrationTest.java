package com.recruitpro.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.*;
import com.recruitpro.candidate.repository.*;
import com.recruitpro.hr.entity.*;
import com.recruitpro.hr.repository.*;
import com.recruitpro.jobs.service.BackgroundJobWorker;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end resume parsing and matching against a running AI service on 127.0.0.1:8000 started with
 * AI_SERVICE_TOKEN=phase5a-test-token-minimum-32-chars-long. Opt-in: set RECRUITPRO_AI_IT=true.
 * Parsing runs through the PARSE_RESUME background job (BackgroundJobWorker.runOnce()).
 */
@SpringBootTest(properties = {
        "app.ai-service.base-url=http://127.0.0.1:8000",
        "app.ai-service.service-token=phase5a-test-token-minimum-32-chars-long"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RECRUITPRO_AI_IT", matches = "true")
class AiLifecycleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository candidateProfiles;
    @Autowired ResumeRepository resumes;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired ApplicationRepository applications;
    @Autowired JobMatchRunRepository matchRuns;
    @Autowired BackgroundJobWorker worker;
    @Autowired com.recruitpro.evidence.service.MatchTriggerService matchTriggerService;
    @Autowired TestDataFactory fixtures;

    private String candidateEmail;
    private String otherCandidateEmail;
    private String hrManagerEmail;
    private CandidateProfile candidate;
    private CandidateProfile otherCandidate;
    private Job testJob;
    private JobRequirement reqSkillMandatory;
    private JobRequirement reqSkillOptional;
    private JobRequirement reqExperienceMandatory;

    @BeforeEach
    void setUp() {
        cleanUp();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        candidateEmail = "candidate-" + uid + "@example.test";
        otherCandidateEmail = "other-" + uid + "@example.test";
        hrManagerEmail = "hrmanager-" + uid + "@example.test";

        candidate = createCandidate(candidateEmail);
        otherCandidate = createCandidate(otherCandidateEmail);

        Organization organization = fixtures.organization("AI Lifecycle Org");
        User manager = fixtures.staff(organization, hrManagerEmail, Role.HR_MANAGER);
        HRProfile hr = new HRProfile();
        hr.setUser(manager);
        hr = hrProfiles.save(hr);

        testJob = new Job();
        testJob.setCreatedBy(hr);
        testJob.setOrganizationId(organization.getId());
        testJob.setTitle("Senior Full-Stack Engineer " + uid);
        testJob.setDescription("Building scalable recruitment platforms");
        testJob.setDepartment("Engineering");
        testJob.setLocation("Remote");
        testJob.setJobType(JobType.FULL_TIME);
        testJob.setExperienceLevel(ExperienceLevel.SENIOR);
        testJob.setMinimumSalary(new BigDecimal("70000.00"));
        testJob.setMaximumSalary(new BigDecimal("120000.00"));
        testJob.setVacancies(2);
        testJob.setStatus(JobStatus.PUBLISHED);
        testJob.setPublishedAt(LocalDateTime.now());
        testJob = jobs.save(testJob);

        reqSkillMandatory = new JobRequirement();
        reqSkillMandatory.setJob(testJob);
        reqSkillMandatory.setType(RequirementType.SKILL);
        reqSkillMandatory.setDescription("Java and Spring Boot");
        reqSkillMandatory.setMandatory(true);
        requirements.save(reqSkillMandatory);

        reqSkillOptional = new JobRequirement();
        reqSkillOptional.setJob(testJob);
        reqSkillOptional.setType(RequirementType.SKILL);
        reqSkillOptional.setDescription("Docker");
        reqSkillOptional.setMandatory(false);
        requirements.save(reqSkillOptional);

        reqExperienceMandatory = new JobRequirement();
        reqExperienceMandatory.setJob(testJob);
        reqExperienceMandatory.setType(RequirementType.EXPERIENCE);
        reqExperienceMandatory.setDescription("enterprise Java services");
        reqExperienceMandatory.setMandatory(true);
        reqExperienceMandatory.setMinimumYears(3);
        requirements.save(reqExperienceMandatory);
    }

    @Test
    @Timeout(30)
    void testPdfResumeLifecycle_UploadToExtractionAndFactCorrection() throws Exception {
        byte[] pdfBytes = loadFixture("sample-valid.pdf");

        // 1. Upload valid PDF resume
        long resumeId = uploadResume(candidateEmail, "sample-valid.pdf", "application/pdf", pdfBytes);
        assertTrue(resumeId > 0);

        // 2. Confirm analysis created in PENDING state
        ResumeAnalysis analysis = analyses.findByResumeId(resumeId).orElseThrow();
        assertEquals(ResumeAnalysisStatus.PENDING, analysis.getStatus());
        assertEquals(0, analysis.getAttemptCount());

        // 3. Worker dispatches pending analysis
        worker.runOnce();
        analysis = awaitTerminalStatus(analysis.getId(), 5000);

        // 4. Confirm completed status and SHA-256
        assertEquals(ResumeAnalysisStatus.COMPLETED, analysis.getStatus());
        assertNotNull(analysis.getSourceSha256());
        assertEquals(64, analysis.getSourceSha256().length());
        assertNotNull(analysis.getCompletedAt());

        // 5. Confirm extracted facts in database
        List<ResumeAnalysisFact> factList = facts.findAllByAnalysisIdOrderById(analysis.getId());
        assertFalse(factList.isEmpty());
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("Java")));
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("Spring Boot")));
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("MySQL")));
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.EDUCATION));

        // 6. Candidate retrieves analysis via API
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", resumeId)
                        .with(user(candidateEmail).roles("CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.facts", not(empty())));

        // 7. Candidate reviews and corrects a fact
        ResumeAnalysisFact javaFact = factList.stream()
                .filter(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("Java"))
                .findFirst().orElseThrow();

        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}", resumeId, javaFact.getId())
                        .with(user(candidateEmail).roles("CANDIDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"Java 17 & 21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value", is("Java 17 & 21")))
                .andExpect(jsonPath("$.data.extractedValue", is("Java")))
                .andExpect(jsonPath("$.data.reviewStatus", is("CORRECTED")));

        // 8. Confirm review status in DB
        ResumeAnalysisFact updatedFact = facts.findById(javaFact.getId()).orElseThrow();
        assertEquals(ResumeFactReviewStatus.CORRECTED, updatedFact.getReviewStatus());
        assertEquals("Java 17 & 21", updatedFact.getReviewedValue());
    }

    @Test
    @Timeout(30)
    void testDocxResumeLifecycle_ExtractionAndContactRedaction() throws Exception {
        byte[] docxBytes = loadFixture("sample-valid.docx");

        long resumeId = uploadResume(candidateEmail, "sample-valid.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes);

        ResumeAnalysis analysis = analyses.findByResumeId(resumeId).orElseThrow();
        assertEquals(ResumeAnalysisStatus.PENDING, analysis.getStatus());

        worker.runOnce();
        analysis = awaitTerminalStatus(analysis.getId(), 5000);

        assertEquals(ResumeAnalysisStatus.COMPLETED, analysis.getStatus());

        List<ResumeAnalysisFact> factList = facts.findAllByAnalysisIdOrderById(analysis.getId());
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("React")));
        assertTrue(factList.stream().anyMatch(f -> f.getType() == ResumeFactType.SKILL && f.getExtractedValue().equals("TypeScript")));

        // Verify contact details are redacted from general evidence snippets
        for (ResumeAnalysisFact f : factList) {
            if (f.getType() != ResumeFactType.EMAIL && f.getType() != ResumeFactType.PHONE) {
                assertFalse(f.getEvidence().contains("dev@example.test"));
                assertFalse(f.getEvidence().contains("555 987"));
            }
        }
    }

    @Test
    @Timeout(30)
    void testScannedDocumentsReturnNeedsOcr() throws Exception {
        byte[] pdfScanned = loadFixture("sample-scanned.pdf");
        long resumeId = uploadResume(candidateEmail, "scanned.pdf", "application/pdf", pdfScanned);

        ResumeAnalysis analysis = analyses.findByResumeId(resumeId).orElseThrow();
        worker.runOnce();
        analysis = awaitTerminalStatus(analysis.getId(), 5000);

        assertEquals(ResumeAnalysisStatus.NEEDS_OCR, analysis.getStatus());
        assertEquals("SCANNED_PDF", analysis.getErrorCode());
        assertTrue(facts.findAllByAnalysisIdOrderById(analysis.getId()).isEmpty());
    }

    @Test
    @Timeout(30)
    void testMalformedAndUnsupportedDocumentHandling() throws Exception {
        // 1. Malformed PDF
        byte[] malformedPdf = loadFixture("sample-malformed.pdf");
        long pdfId = uploadResume(candidateEmail, "malformed.pdf", "application/pdf", malformedPdf);

        ResumeAnalysis pdfAnalysis = analyses.findByResumeId(pdfId).orElseThrow();
        worker.runOnce();
        pdfAnalysis = awaitTerminalStatus(pdfAnalysis.getId(), 5000);

        assertEquals(ResumeAnalysisStatus.FAILED, pdfAnalysis.getStatus());
        assertEquals("MALFORMED_PDF", pdfAnalysis.getErrorCode());

        // 2. Legacy .doc format is queued for the AI service, which parses it (LibreOffice or olefile)
        byte[] legacyDoc = loadFixture("sample-legacy.doc");
        long docId = uploadResume(candidateEmail, "legacy.doc", "application/msword", legacyDoc);

        ResumeAnalysis docAnalysis = analyses.findByResumeId(docId).orElseThrow();
        assertEquals(ResumeAnalysisStatus.PENDING, docAnalysis.getStatus());
        worker.runOnce();
        docAnalysis = awaitTerminalStatus(docAnalysis.getId(), 5000);
        assertTrue(docAnalysis.getStatus() != ResumeAnalysisStatus.UNSUPPORTED_FORMAT,
                "legacy .doc must be sent to the AI service, not rejected by the backend");
    }

    @Test
    @Timeout(30)
    void testHrMatchingAndRankingIntegration() throws Exception {
        // Candidate 1: Upload valid PDF with Java, Spring Boot, MySQL, 4 years experience
        byte[] pdfBytes = loadFixture("sample-valid.pdf");
        long resume1Id = uploadResume(candidateEmail, "cand1.pdf", "application/pdf", pdfBytes);
        worker.runOnce();
        ResumeAnalysis analysis1 = awaitTerminalStatus(analyses.findByResumeId(resume1Id).orElseThrow().getId(), 5000);
        assertEquals(ResumeAnalysisStatus.COMPLETED, analysis1.getStatus());

        // Candidate 1 applies to testJob
        Application app1 = new Application();
        app1.setCandidate(candidate);
        app1.setJob(testJob);
        app1.setResume(resumes.findById(resume1Id).orElseThrow());
        app1.setStatus(ApplicationStatus.SUBMITTED);
        app1.setAppliedAt(LocalDateTime.now().minusHours(1));
        app1 = applications.save(app1);

        // Candidate 2: Upload valid DOCX with React/TypeScript
        byte[] docxBytes = loadFixture("sample-valid.docx");
        long resume2Id = uploadResume(otherCandidateEmail, "cand2.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes);
        worker.runOnce();
        ResumeAnalysis analysis2 = awaitTerminalStatus(analyses.findByResumeId(resume2Id).orElseThrow().getId(), 5000);
        assertEquals(ResumeAnalysisStatus.COMPLETED, analysis2.getStatus());

        // Candidate 2 applies to testJob
        Application app2 = new Application();
        app2.setCandidate(otherCandidate);
        app2.setJob(testJob);
        app2.setResume(resumes.findById(resume2Id).orElseThrow());
        app2.setStatus(ApplicationStatus.SUBMITTED);
        app2.setAppliedAt(LocalDateTime.now().minusHours(2));
        app2 = applications.save(app2);

        // Matching is asynchronous (D7): queue it as an application submission would, then run the worker.
        matchTriggerService.requestMatch(app1.getId());
        matchTriggerService.requestMatch(app2.getId());
        worker.runOnce();

        // HR Manager views the server-ranked applicants; the GET itself never calls the AI service.
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", testJob.getId())
                        .with(user(hrManagerEmail).roles("HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].applicationId", is(app1.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].matchStatus", is("READY")))
                .andExpect(jsonPath("$.data.content[0].rank", is(1)))
                .andExpect(jsonPath("$.data.content[0].verifiedCoverage", greaterThan(0.0)))
                .andExpect(jsonPath("$.data.content[1].applicationId", is(app2.getId().intValue())));

        // Confirm the append-only run persisted in job_match_runs
        JobMatchRun run = matchRuns.findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(app1.getId()).orElseThrow();
        assertTrue(run.getVerifiedCoverage().compareTo(BigDecimal.ZERO) > 0);
        assertEquals("evidence-ladder-4", run.getAlgorithmVersion());
        assertEquals("INITIAL", run.getTriggerReason());
        assertNotNull(run.getRequirementResultsJson());
    }

    @Test
    @Timeout(30)
    void testCrossCandidateIsolationAndHrAuthorizationSecurity() throws Exception {
        byte[] pdfBytes = loadFixture("sample-valid.pdf");
        long resumeId = uploadResume(candidateEmail, "private.pdf", "application/pdf", pdfBytes);

        // 1. Candidate 2 cannot view Candidate 1's resume analysis (404)
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", resumeId)
                        .with(user(otherCandidateEmail).roles("CANDIDATE")))
                .andExpect(status().isNotFound());

        // 2. Candidate 1 cannot view non-existent resume analysis (404)
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", 999999L)
                        .with(user(candidateEmail).roles("CANDIDATE")))
                .andExpect(status().isNotFound());

        // 3. Candidate cannot access HR applicant intelligence endpoint (403)
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", testJob.getId())
                        .with(user(candidateEmail).roles("CANDIDATE")))
                .andExpect(status().isForbidden());

        // 4. Anonymous cannot access HR applicant intelligence (401)
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", testJob.getId()))
                .andExpect(status().isUnauthorized());
    }

    private long uploadResume(String email, String filename, String contentType, byte[] bytes) throws Exception {
        String json = mvc.perform(multipart("/api/v1/candidates/me/resumes")
                        .file(new MockMultipartFile("file", filename, contentType, bytes))
                        .with(user(email).roles("CANDIDATE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asLong();
    }

    private ResumeAnalysis awaitTerminalStatus(Long analysisId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            ResumeAnalysis cur = analyses.findById(analysisId).orElse(null);
            if (cur != null && cur.getStatus() != ResumeAnalysisStatus.PENDING && cur.getStatus() != ResumeAnalysisStatus.PROCESSING) {
                return cur;
            }
            Thread.sleep(100);
        }
        return analyses.findById(analysisId).orElse(null);
    }

    private byte[] loadFixture(String name) throws Exception {
        ClassPathResource res = new ClassPathResource("test-resumes/" + name);
        try (InputStream is = res.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private CandidateProfile createCandidate(String email) {
        User user = createUser(email, Role.CANDIDATE);
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(user);
        return candidateProfiles.save(profile);
    }

    private User createUser(String email, Role role) {
        return users.save(User.builder()
                .email(email)
                .passwordHash("hashed-password-123456")
                .firstName("Test")
                .lastName("User")
                .role(role)
                .enabled(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        fixtures.cleanDatabase();
    }
}
