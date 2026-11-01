package com.recruitpro.hr;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.application.service.ApplicationService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.JobType;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.dto.ApplicationDecisionRequest;
import com.recruitpro.hr.service.ApplicationReviewService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "app.ai-service.poll-interval-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationReviewAuthorizationIntegrationTest {
    private static final String REVIEW_ROUTE = "/api/v1/hr/jobs/{jobId}/applicants/{applicationId}";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository candidates;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired ResumeRepository resumes;
    @Autowired ApplicationRepository applications;
    @Autowired com.recruitpro.candidate.repository.ResumeAnalysisRepository resumeAnalyses;
    @Autowired com.recruitpro.candidate.repository.ResumeAnalysisFactRepository resumeFacts;
    @Autowired com.recruitpro.hr.repository.JobMatchRunRepository matchRuns;
    @Autowired ApplicationReviewService reviewService;
    @Autowired ApplicationService applicationService;
    @SpyBean ApplicationDecisionHistoryRepository history;
    @PersistenceContext EntityManager entityManager;

    private String ownerEmail;
    private String otherHrEmail;
    private String adminEmail;
    private CandidateProfile candidate;
    private CandidateProfile secondCandidate;
    private Job job;
    private Application application;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerEmail = "review-owner-" + suffix + "@example.test";
        otherHrEmail = "review-other-hr-" + suffix + "@example.test";
        adminEmail = "review-admin-" + suffix + "@example.test";
        candidate = createCandidate("review-candidate-" + suffix + "@example.test");
        secondCandidate = createCandidate("review-candidate-two-" + suffix + "@example.test");

        HRProfile owner = createHr(ownerEmail, Role.HR_MANAGER);
        createHr(otherHrEmail, Role.HR_MANAGER);
        createUser(adminEmail, Role.ADMIN);
        job = createJob(owner);
        application = createApplication(candidate, job);
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
    void unauthenticatedRequestsAreDeniedByTheRealHttpSecurityChain() throws Exception {
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"review\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Timeout(45)
    void nonHrRolesIncludingRecruiterCannotDecideCommentOrReadHistory() throws Exception {
        for (String role : List.of("CANDIDATE", "RECRUITER", "INTERVIEWER")) {
            var principal = user(role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test").roles(role);
            mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                            .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                            .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                            .with(principal).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"review\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(principal))
                    .andExpect(status().isForbidden());
        }
        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(application.getId()).orElseThrow().getStatus());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());
    }

    @Test
    @Timeout(45)
    void nonOwnerHrCannotDecideCommentOrReadOwnerScopedHistory() throws Exception {
        var principal = user(otherHrEmail).roles("HR_MANAGER");
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"review\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(principal))
                .andExpect(status().isForbidden());

        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(application.getId()).orElseThrow().getStatus());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());
    }

    @Test
    @Timeout(45)
    void jobOwnerAndAdminRetainDecisionCommentAndHistoryAccess() throws Exception {
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(user(ownerEmail).roles("HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Strong evidence\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.newStatus", is("SHORTLISTED")));
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                        .with(user(ownerEmail).roles("HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Reviewed by owner\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId())
                        .with(user(ownerEmail).roles("HR_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(2)));

        Application adminApplication = createApplication(secondCandidate, job);
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), adminApplication.getId())
                        .with(user(adminEmail).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Policy decision\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.newStatus", is("REJECTED")));
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), adminApplication.getId())
                        .with(user(adminEmail).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Admin follow-up\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), adminApplication.getId())
                        .with(user(adminEmail).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(2)));
    }

    @Test
    @Timeout(45)
    void failedAuditFlushRollsBackTheApplicationUpdateAndHistoryWriteTogether() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(ownerEmail, "test-only",
                List.of(new SimpleGrantedAuthority("ROLE_HR_MANAGER"))));
        SecurityContextHolder.setContext(context);
        AtomicBoolean auditInsertFlushed = new AtomicBoolean(false);
        try {
            doAnswer(invocation -> {
                ApplicationDecisionHistory event = invocation.getArgument(0);
                // Mirrors Spring Data's saveAndFlush for a new entity while keeping the write
                // in the service transaction, then injects a failure after the SQL has flushed.
                entityManager.persist(event);
                entityManager.flush();
                auditInsertFlushed.set(event.getId() != null);
                throw new IllegalStateException("synthetic failure after audit insert flush");
            }).when(history).saveAndFlush(any(ApplicationDecisionHistory.class));

            Assertions.assertThrows(IllegalStateException.class, () -> reviewService.decide(job.getId(),
                    application.getId(), ApplicationStatus.SHORTLISTED,
                    new com.recruitpro.hr.dto.ApplicationDecisionRequest("must roll back")));
        } finally {
            reset(history);
            SecurityContextHolder.clearContext();
        }

        assertTrue(auditInsertFlushed.get(), "the audit insert must have flushed before the synthetic failure");
        Application afterFailure = applications.findById(application.getId()).orElseThrow();
        assertEquals(ApplicationStatus.SUBMITTED, afterFailure.getStatus());
        assertNull(afterFailure.getReviewComment());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());
    }

    @Test
    @Timeout(45)
    void concurrentHrDecisionAndCandidateWithdrawalCommitOnlyOneConsistentOutcome() throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> decision = workers.submit(concurrentRequest(ownerEmail, "HR_MANAGER", start,
                    () -> reviewService.decide(job.getId(), application.getId(), ApplicationStatus.SHORTLISTED,
                            new ApplicationDecisionRequest("synthetic HR decision"))));
            Future<Boolean> withdrawal = workers.submit(concurrentRequest(candidate.getUser().getEmail(), "CANDIDATE", start,
                    () -> applicationService.withdraw(application.getId())));
            start.await(10, TimeUnit.SECONDS);

            boolean decisionSucceeded = decision.get(20, TimeUnit.SECONDS);
            boolean withdrawalSucceeded = withdrawal.get(20, TimeUnit.SECONDS);
            assertNotEquals(decisionSucceeded, withdrawalSucceeded,
                    "the pessimistic application lock permits exactly one transition from SUBMITTED");

            Application persisted = applications.findById(application.getId()).orElseThrow();
            List<ApplicationDecisionHistory> events = history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId());
            if (decisionSucceeded) {
                assertEquals(ApplicationStatus.SHORTLISTED, persisted.getStatus());
                assertEquals("synthetic HR decision", persisted.getReviewComment());
                assertEquals(1, events.size());
                assertEquals(ApplicationDecisionAction.SHORTLIST, events.get(0).getActionType());
                assertEquals(ApplicationStatus.SUBMITTED, events.get(0).getPreviousStatus());
                assertEquals(ApplicationStatus.SHORTLISTED, events.get(0).getNewStatus());
            } else {
                assertEquals(ApplicationStatus.WITHDRAWN, persisted.getStatus());
                assertNull(persisted.getReviewComment());
                assertEquals(0, events.size());
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Callable<Boolean> concurrentRequest(String email, String role, CyclicBarrier start, Runnable operation) {
        return () -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(email, "test-only",
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            SecurityContextHolder.setContext(context);
            try {
                start.await(10, TimeUnit.SECONDS);
                try {
                    operation.run();
                    return true;
                } catch (BadRequestException expectedTransitionRejection) {
                    return false;
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private CandidateProfile createCandidate(String email) {
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(createUser(email, Role.CANDIDATE));
        return candidates.save(profile);
    }

    private HRProfile createHr(String email, Role role) {
        HRProfile profile = new HRProfile();
        profile.setUser(createUser(email, role));
        return hrProfiles.save(profile);
    }

    private User createUser(String email, Role role) {
        return users.save(User.builder().email(email).passwordHash("test-only").firstName("Test")
                .lastName("User").role(role).enabled(true).build());
    }

    private Job createJob(HRProfile owner) {
        Job created = new Job();
        created.setCreatedBy(owner);
        created.setTitle("Synthetic Review Job");
        created.setDescription("Synthetic job description for authorization tests");
        created.setDepartment("Engineering");
        created.setLocation("Remote");
        created.setJobType(JobType.FULL_TIME);
        created.setExperienceLevel(ExperienceLevel.MID);
        created.setMinimumSalary(new BigDecimal("50000"));
        created.setMaximumSalary(new BigDecimal("90000"));
        created.setVacancies(1);
        created.setStatus(JobStatus.PUBLISHED);
        created.setPublishedAt(LocalDateTime.now());
        return jobs.save(created);
    }

    private Application createApplication(CandidateProfile applicant, Job targetJob) {
        Resume resume = new Resume();
        resume.setCandidate(applicant);
        resume.setStorageKey(UUID.randomUUID().toString());
        resume.setOriginalFilename("synthetic.pdf");
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(1);
        resume.setUploadedAt(LocalDateTime.now());
        resume = resumes.save(resume);

        Application created = new Application();
        created.setCandidate(applicant);
        created.setJob(targetJob);
        created.setResume(resume);
        created.setStatus(ApplicationStatus.SUBMITTED);
        created.setAppliedAt(LocalDateTime.now());
        return applications.saveAndFlush(created);
    }

    private Application createApplicationWithStatus(CandidateProfile applicant, Job targetJob, ApplicationStatus status) {
        Application app = createApplication(applicant, targetJob);
        app.setStatus(status);
        return applications.saveAndFlush(app);
    }

    @Test
    @Timeout(45)
    void ownerAndAdminAndRecruiterCanListApplicantsWithStatusFiltering() throws Exception {
        CandidateProfile candShortlisted = createCandidate("review-shortlisted-" + UUID.randomUUID() + "@example.test");
        CandidateProfile candRejected = createCandidate("review-rejected-" + UUID.randomUUID() + "@example.test");
        CandidateProfile candWithdrawn = createCandidate("review-withdrawn-" + UUID.randomUUID() + "@example.test");

        createApplicationWithStatus(candShortlisted, job, ApplicationStatus.SHORTLISTED);
        createApplicationWithStatus(candRejected, job, ApplicationStatus.REJECTED);
        createApplicationWithStatus(candWithdrawn, job, ApplicationStatus.WITHDRAWN);

        var ownerPrincipal = user(ownerEmail).roles("HR_MANAGER");

        // 1. List all (no status filter) -> 4 total
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(4)));

        // 2. Filter SUBMITTED -> 1
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("status", "SUBMITTED").with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].applicationStatus", is("SUBMITTED")));

        // 3. Filter SHORTLISTED -> 1
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("status", "SHORTLISTED").with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].applicationStatus", is("SHORTLISTED")));

        // 4. Filter REJECTED -> 1
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("status", "REJECTED").with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].applicationStatus", is("REJECTED")));

        // 5. Filter WITHDRAWN -> 1
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("status", "WITHDRAWN").with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].applicationStatus", is("WITHDRAWN")));

        // 6. Admin can also list
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user(adminEmail).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(4)));

        // 7. Recruiter can also list
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user("recruiter-" + UUID.randomUUID() + "@example.test").roles("RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(4)));
    }

    @Test
    @Timeout(45)
    void nonManagingHrAndCandidateCannotListApplicantsAndUnauthenticatedReturns401() throws Exception {
        // Unauthenticated -> 401
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()))
                .andExpect(status().isUnauthorized());

        // Candidate -> 403
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user(candidate.getUser().getEmail()).roles("CANDIDATE")))
                .andExpect(status().isForbidden());

        // Non-owner HR -> 403
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user(otherHrEmail).roles("HR_MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @Timeout(45)
    void invalidStatusTransitionsAreRejectedWithBadRequest() throws Exception {
        var ownerPrincipal = user(ownerEmail).roles("HR_MANAGER");

        // 1. Shortlist from SUBMITTED -> OK
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // 2. Repeated shortlist on already SHORTLISTED -> 400
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 3. Reject on already SHORTLISTED -> 400
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 4. Withdrawn application cannot be shortlisted or rejected
        CandidateProfile candWithdrawn = createCandidate("review-withdrawn-app-" + UUID.randomUUID() + "@example.test");
        Application withdrawnApp = createApplicationWithStatus(candWithdrawn, job, ApplicationStatus.WITHDRAWN);

        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), withdrawnApp.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), withdrawnApp.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 5. Updating comment on SUBMITTED application before any decision is taken -> 400
        CandidateProfile candSubmitted = createCandidate("review-submitted-app-" + UUID.randomUUID() + "@example.test");
        Application submittedApp = createApplication(candSubmitted, job);

        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), submittedApp.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Early comment\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Timeout(45)
    void malformedReviewRequestsAndPaginationAreRejected() throws Exception {
        var ownerPrincipal = user(ownerEmail).roles("HR_MANAGER");

        // Negative page -> 400
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("page", "-1").with(ownerPrincipal))
                .andExpect(status().isBadRequest());

        // Zero size -> 400
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("size", "0").with(ownerPrincipal))
                .andExpect(status().isBadRequest());

        // Size > 50 -> 400
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("size", "51").with(ownerPrincipal))
                .andExpect(status().isBadRequest());

        // Comment > 2000 chars -> 400
        String oversizedComment = "x".repeat(2001);
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + oversizedComment + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Timeout(45)
    void aiRecommendationsNeverReplaceHumanDecision() throws Exception {
        var ownerPrincipal = user(ownerEmail).roles("HR_MANAGER");

        // Application has no AI analysis yet (null score and analysis status)
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].analysisStatus").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].observedEvidenceCoverage").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].matchingErrorCode", is("ANALYSIS_NOT_AVAILABLE")));

        // HR makes independent human decision
        String commentText = "Human HR evaluation: Strong fit verified in technical interview";
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + commentText + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("SHORTLISTED")))
                .andExpect(jsonPath("$.data.actionType", is("SHORTLIST")))
                .andExpect(jsonPath("$.data.actorName", is("Test User")))
                .andExpect(jsonPath("$.data.comment", is(commentText)));

        // Audit history reflects human actor and timestamp
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].actionType", is("SHORTLIST")))
                .andExpect(jsonPath("$.data[0].actorName", is("Test User")))
                .andExpect(jsonPath("$.data[0].comment", is(commentText)));
    }
}
