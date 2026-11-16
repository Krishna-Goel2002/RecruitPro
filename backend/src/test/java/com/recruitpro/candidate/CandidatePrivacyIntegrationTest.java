package com.recruitpro.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.assessment.repository.WorkSampleSubmissionFileRepository;
import com.recruitpro.assessment.entity.WorkSampleSubmissionFile;
import com.recruitpro.audit.entity.AuditEvent;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.service.RefreshTokenService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.privacy.CandidateAccountDeletionService;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.fairness.entity.EeoResponse;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.AuthTestSupport;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Candidate privacy rights (plan 5.5, D14): the JSON export contains the candidate's own records but no staff
 * material; account deletion needs the password (wrong password: 400), anonymises the account, deletes files,
 * facts, claims, survey and notifications, keeps anonymised applications, decisions and audit rows, revokes
 * sessions and clears the refresh cookie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidatePrivacyIntegrationTest {

    private static final String EMAIL = "privacy@priv-cand.test";
    private static final String PASSWORD = "Correct-Horse-42";
    private static final byte[] FILE = "%PDF-1.4 test resume".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository profiles;
    @Autowired ResumeRepository resumes;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired ResumeStorage resumeStorage;
    @Autowired CandidateClaimRepository claims;
    @Autowired EeoResponseRepository eeoResponses;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationDecisionHistoryRepository history;
    @Autowired WorkSampleSubmissionFileRepository submissionFiles;
    @Autowired WorkSampleRepository workSamples;
    @Autowired WorkSampleAssignmentRepository assignments;
    @Autowired InterviewRepository interviews;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService notificationService;
    @Autowired AuditService auditService;
    @Autowired AuditEventRepository auditEvents;
    @Autowired RefreshTokenService refreshTokenService;

    User user;
    User managerUser;
    CandidateProfile profile;
    Resume resume;
    ResumeAnalysis analysis;
    Application application;
    WorkSampleAssignment assignment;
    Interview interview;
    String refreshToken;
    Job job;

    @BeforeEach
    void setUp() throws IOException {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Privacy Org");
        HRProfile manager = fixtures.hrMember(organization, "manager@priv.test", Role.HR_MANAGER);
        managerUser = manager.getUser();
        job = fixtures.job(manager, "Payroll Specialist", JobStatus.PUBLISHED);

        profile = fixtures.candidate(EMAIL);
        user = profile.getUser();
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Priya");
        user.setLastName("Sharma");
        user = users.save(user);
        profile.setCity("Bhubaneswar");
        profile.setLinkedinUrl("https://linkedin.example.test/priya");
        profile = profiles.save(profile);

        resume = screening.resume(profile, "priya-sharma-cv.pdf");
        resumeStorage.store(resume.getStorageKey(), FILE);
        analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        analysis.setTimelineJson("{\"totalMonths\":30,\"roles\":[{\"employer\":\"Kalinga Steel\"}],\"skillMonths\":{}}");
        analysis = analyses.save(analysis);
        screening.fact(analysis, ResumeFactType.SKILL, "Tally");
        screening.fact(analysis, ResumeFactType.EMAIL, EMAIL);

        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(profile);
        claim.setClaimType(ClaimType.CERTIFICATION);
        claim.setClaimValue("Chartered Accountant");
        claims.save(claim);

        EeoResponse survey = new EeoResponse();
        survey.setCandidateId(profile.getId());
        survey.setGender("WOMAN");
        survey.setConsentGivenAt(UtcTime.now());
        eeoResponses.save(survey);

        application = screening.application(profile, job, resume, UtcTime.now().minusDays(3), "My cover letter");
        application.setIdentityRevealedAt(UtcTime.now().minusDays(1));
        application.setReviewComment("INTERNAL-COMMENT");
        application = screening.withStatus(application, ApplicationStatus.SHORTLISTED);
        ApplicationDecisionHistory shortlist = new ApplicationDecisionHistory();
        shortlist.setApplication(application);
        shortlist.setPreviousStatus(ApplicationStatus.SUBMITTED);
        shortlist.setNewStatus(ApplicationStatus.SHORTLISTED);
        shortlist.setActionType(ApplicationDecisionAction.SHORTLIST);
        shortlist.setActor(managerUser);
        shortlist.setComment("INTERNAL-DECISION-NOTE");
        history.save(shortlist);

        WorkSample sample = new WorkSample();
        sample.setJob(job);
        sample.setTitle("Payroll task");
        sample.setInstructions("Compute the payroll");
        sample.setTimeLimitMinutes(30);
        sample.setRubricJson("[]");
        sample.setStatus(WorkSampleStatus.ACTIVE);
        sample = workSamples.save(sample);
        WorkSampleAssignment newAssignment = new WorkSampleAssignment();
        newAssignment.setWorkSample(sample);
        newAssignment.setApplication(application);
        newAssignment.setStatus(WorkSampleAssignmentStatus.SCORED);
        newAssignment.setAssignedAt(UtcTime.now().minusDays(1));
        newAssignment.setStartedAt(UtcTime.now().minusHours(20));
        newAssignment.setSubmittedAt(UtcTime.now().minusHours(19));
        newAssignment.setResponseText("My payroll answer");
        newAssignment.setScoresJson("[{\"requirementId\":null,\"criterion\":\"Accuracy\",\"score\":4,\"note\":\"SCORE-NOTE\"}]");
        newAssignment.setTotalScore(new java.math.BigDecimal("4.00"));
        newAssignment.setScoredByUserId(managerUser.getId());
        newAssignment.setScoredAt(UtcTime.now().minusHours(1));
        assignment = assignments.save(newAssignment);

        Interview newInterview = new Interview();
        newInterview.setApplication(application);
        newInterview.setInterviewerUserId(managerUser.getId());
        newInterview.setScheduledAt(UtcTime.now().plusDays(2));
        newInterview.setDurationMinutes(45);
        newInterview.setStatus(InterviewStatus.SCHEDULED);
        interview = interviews.save(newInterview);

        notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.APPLICATION_RECEIVED,
                Map.of("firstName", "Priya", "jobTitle", "Payroll Specialist", "organizationName", "Privacy Org"));
        auditService.recordFor(null, user.getId(), EMAIL, AuditActions.PASSWORD_CHANGED, "USER",
                String.valueOf(user.getId()), "Password changed");
        auditService.recordFor(organization.getId(), managerUser.getId(), managerUser.getEmail(),
                AuditActions.APPLICATION_DECISION, "APPLICATION", String.valueOf(application.getId()), "Shortlisted");
        refreshToken = refreshTokenService.issueNewFamily(user);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void theExportContainsTheCandidatesRecordsButNoStaffMaterial() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/candidates/me/export").with(candidate()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("recruitpro-my-data-")))
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode export = mapper.readTree(body);
        assertEquals("recruitpro-candidate-export-1", export.path("format").asText());
        assertEquals(EMAIL, export.path("account").path("email").asText());
        assertEquals("Bhubaneswar", export.path("profile").path("city").asText());
        assertEquals("priya-sharma-cv.pdf", export.path("resumes").path(0).path("originalFilename").asText());
        assertEquals("Kalinga Steel", export.path("resumes").path(0).path("timeline").path("roles").path(0).path("employer").asText());
        assertEquals(2, export.path("facts").size());
        assertEquals("Chartered Accountant", export.path("claims").path(0).path("value").asText());
        assertEquals("My cover letter", export.path("applications").path(0).path("coverLetter").asText());
        assertEquals(2, export.path("applications").path(0).path("timeline").size());
        assertEquals("My payroll answer", export.path("workSamples").path(0).path("responseText").asText());
        assertEquals(1, export.path("interviews").size());
        assertEquals("WOMAN", export.path("equalOpportunity").path("gender").asText());
        assertEquals(2, export.path("notifications").size());
        assertTrue(export.path("notifications").path(1).path("body").asText().contains("Payroll Specialist"));
        for (String staffOnly : List.of("INTERNAL-COMMENT", "INTERNAL-DECISION-NOTE", "SCORE-NOTE", "totalScore",
                "manager@priv.test", "recommendation")) {
            assertFalse(body.contains(staffOnly), "the export must not contain " + staffOnly);
        }
        mvc.perform(get("/api/v1/candidates/me/export").with(staff())).andExpect(status().isForbidden());
    }

    @Test
    void theExportListsSubmittedWorkSampleFilesAndFactAttributes() throws Exception {
        // R2-L04: WP-PLAT submission files and the parser attributes of facts are personal data too.
        WorkSampleSubmissionFile file = new WorkSampleSubmissionFile();
        file.setAssignmentId(assignment.getId());
        file.setOriginalName("payroll-answer.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(12);
        file.setStoragePath(java.util.UUID.randomUUID().toString());
        file.setSha256("a".repeat(64));
        submissionFiles.save(file);
        var tally = facts.findAllByAnalysisIdOrderById(analysis.getId()).get(0);
        tally.setAttributesJson("{\"skillId\":\"tally\",\"domain\":\"FINANCE\"}");
        facts.save(tally);

        JsonNode export = mapper.readTree(mvc.perform(get("/api/v1/candidates/me/export").with(candidate()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode files = export.path("workSamples").path(0).path("files");
        assertEquals(1, files.size());
        assertEquals("payroll-answer.pdf", files.path(0).path("originalName").asText());
        assertEquals("tally", export.path("facts").path(0).path("attributes").path("skillId").asText());
    }

    @Test
    void deletionNeedsThePasswordAndAnonymisesWhileKeepingAuditableRecords() throws Exception {
        long auditRowsBefore = auditEvents.count();
        mvc.perform(deleteAccount("{\"password\":\"wrong-password-1\"}")).andExpect(status().isBadRequest());
        mvc.perform(deleteAccount("{\"password\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(deleteAccount("{}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/candidates/me").with(staff()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isForbidden());
        assertEquals(EMAIL, users.findById(user.getId()).orElseThrow().getEmail(), "nothing changes on failure");
        assertArrayEquals(FILE, resumeStorage.load(resume.getStorageKey()));

        MvcResult result = mvc.perform(deleteAccount("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("", AuthTestSupport.refreshCookie(result), "the refresh cookie is cleared");

        User deleted = users.findById(user.getId()).orElseThrow();
        assertEquals(CandidateAccountDeletionService.anonymousEmail(user.getId()), deleted.getEmail());
        assertEquals("Deleted", deleted.getFirstName());
        assertEquals("Candidate", deleted.getLastName());
        assertFalse(deleted.isEnabled());
        assertNotNull(deleted.getDeletedAt());
        assertFalse(passwordEncoder.matches(PASSWORD, deleted.getPasswordHash()));
        CandidateProfile anonymised = profiles.findById(profile.getId()).orElseThrow();
        assertNull(anonymised.getCity());
        assertNull(anonymised.getLinkedinUrl());

        Resume keptResume = resumes.findById(resume.getId()).orElseThrow();
        assertEquals("deleted-resume.pdf", keptResume.getOriginalFilename());
        assertThrows(IOException.class, () -> resumeStorage.load(resume.getStorageKey()), "the file is deleted");
        assertTrue(facts.findAllByAnalysisIdOrderById(analysis.getId()).isEmpty(), "facts are deleted");
        assertNull(analyses.findById(analysis.getId()).orElseThrow().getTimelineJson());
        assertEquals(0, claims.countByCandidateId(profile.getId()));
        assertTrue(eeoResponses.findByCandidateId(profile.getId()).isEmpty());
        assertTrue(notifications.findAllByRecipientUserIdOrderByIdAsc(user.getId()).isEmpty());

        Application kept = applications.findById(application.getId()).orElseThrow();
        assertEquals(ApplicationStatus.WITHDRAWN, kept.getStatus(), "open applications are withdrawn");
        assertNull(kept.getCoverLetter());
        List<ApplicationDecisionHistory> events = history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId());
        assertEquals(2, events.size(), "decisions are kept");
        assertEquals(ApplicationDecisionAction.SHORTLIST, events.get(0).getActionType());
        assertEquals(ApplicationDecisionAction.WITHDRAW, events.get(1).getActionType());
        WorkSampleAssignment keptAssignment = assignments.findById(assignment.getId()).orElseThrow();
        assertNull(keptAssignment.getResponseText());
        assertEquals(WorkSampleAssignmentStatus.SCORED, keptAssignment.getStatus());
        assertNotNull(keptAssignment.getTotalScore(), "staff evaluations stay for audit");
        assertEquals(InterviewStatus.CANCELLED, interviews.findById(interview.getId()).orElseThrow().getStatus());

        assertEquals(auditRowsBefore + 1, auditEvents.count(), "audit rows are kept; one is added");
        List<AuditEvent> mine = auditEvents.findAllByActorUserIdOrderByIdAsc(user.getId());
        assertEquals(2, mine.size());
        for (AuditEvent event : mine) {
            assertEquals(CandidateAccountDeletionService.anonymousEmail(user.getId()), event.getActorEmail());
        }
        assertEquals(AuditActions.CANDIDATE_ACCOUNT_DELETED, mine.get(1).getAction());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.APPLICATION_DECISION).size());
        assertFalse(auditEvents.findAll().stream().anyMatch(event -> EMAIL.equals(event.getActorEmail())
                || (event.getDetails() != null && event.getDetails().contains(EMAIL))));

        assertThrows(UnauthorizedException.class, () -> refreshTokenService.rotate(refreshToken), "sessions are revoked");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}", job.getId(), application.getId())
                        .with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.fullName", org.hamcrest.Matchers.is("Deleted Candidate")))
                .andExpect(jsonPath("$.data.applicationStatus", org.hamcrest.Matchers.is("WITHDRAWN")))
                .andExpect(jsonPath("$.data.coverLetter").doesNotExist());
    }

    @Test
    void anInvalidPortalHeaderIsRefusedBeforeTheAccountIsDeleted() throws Exception {
        // Integration pass (R5#2): the portal header was resolved after the deletion, so a bad header deleted the
        // account and then answered 400 with the refresh cookie left in place.
        mvc.perform(delete("/api/v1/candidates/me").with(candidate()).header("X-RecruitPro-Portal", "kiosk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        User intact = users.findById(user.getId()).orElseThrow();
        assertEquals(EMAIL, intact.getEmail());
        assertNull(intact.getDeletedAt());
        assertArrayEquals(FILE, resumeStorage.load(resume.getStorageKey()));
    }

    @Test
    void theExportWritesNullsOfTheProfileAndClaimsExplicitly() throws Exception {
        // Integration pass (R5#7): the profile and claims used the API's non_null records, so empty fields vanished.
        JsonNode export = mapper.readTree(mvc.perform(get("/api/v1/candidates/me/export").with(candidate()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(export.path("profile").has("portfolioUrl"), "an empty profile field is written");
        assertTrue(export.path("profile").get("portfolioUrl").isNull());
        assertTrue(export.path("claims").path(0).has("evidenceUrl"));
        assertTrue(export.path("claims").path(0).get("evidenceUrl").isNull());
        assertTrue(export.path("claims").path(0).get("verifiedAt").isNull());
        assertEquals("UNVERIFIED", export.path("claims").path(0).path("verificationStatus").asText());
    }

    private org.springframework.test.web.servlet.RequestBuilder deleteAccount(String body) {
        return delete("/api/v1/candidates/me").with(candidate()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidate() {
        return SecurityMockMvcRequestPostProcessors.user(EMAIL).roles("CANDIDATE");
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff() {
        return SecurityMockMvcRequestPostProcessors.user("manager@priv.test").roles("HR_MANAGER");
    }
}
