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
import com.recruitpro.hr.dto.DecisionRequest;
import com.recruitpro.hr.service.ApplicationDecisionService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
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

@SpringBootTest
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
    @Autowired ApplicationDecisionService decisionService;
    @Autowired ApplicationService applicationService;
    @SpyBean ApplicationDecisionHistoryRepository history;
    @PersistenceContext EntityManager entityManager;
    @Autowired TestDataFactory fixtures;

    private String ownerEmail;
    private String colleagueHrEmail;
    private String otherHrEmail;
    private String adminEmail;
    private String recruiterEmail;
    private Organization organization;
    private CandidateProfile candidate;
    private CandidateProfile secondCandidate;
    private Job job;
    private Application application;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        String suffix = UUID.randomUUID().toString();
        ownerEmail = "review-owner-" + suffix + "@example.test";
        colleagueHrEmail = "review-colleague-hr-" + suffix + "@example.test";
        otherHrEmail = "review-other-org-hr-" + suffix + "@example.test";
        adminEmail = "review-admin-" + suffix + "@example.test";
        recruiterEmail = "review-recruiter-" + suffix + "@example.test";
        candidate = createCandidate("review-candidate-" + suffix + "@example.test");
        secondCandidate = createCandidate("review-candidate-two-" + suffix + "@example.test");

        organization = fixtures.organization("Review Org");
        Organization otherOrganization = fixtures.organization("Other Review Org");
        HRProfile owner = createHr(organization, ownerEmail, Role.HR_MANAGER);
        createHr(organization, colleagueHrEmail, Role.HR_MANAGER);
        createHr(otherOrganization, otherHrEmail, Role.HR_MANAGER);
        fixtures.staff(organization, adminEmail, Role.ADMIN);
        fixtures.staff(organization, recruiterEmail, Role.RECRUITER);
        job = createJob(owner);
        application = createApplication(candidate, job);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
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
    void candidatesAndInterviewersCannotReviewAndRecruitersCannotDecide() throws Exception {
        for (String role : List.of("CANDIDATE", "INTERVIEWER")) {
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
        var recruiter = user(recruiterEmail).roles("RECRUITER");
        for (String decision : List.of("/shortlist", "/reject", "/reveal")) {
            mvc.perform(post(REVIEW_ROUTE + decision, job.getId(), application.getId())
                            .with(recruiter).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(REVIEW_ROUTE + "/decisions", job.getId(), application.getId())
                        .with(recruiter).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHORTLIST\"}"))
                .andExpect(status().isForbidden());
        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(application.getId()).orElseThrow().getStatus());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());

        // Recruiters of the organisation comment and read the history (plan 5.4).
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                        .with(recruiter).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Recruiter note\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.actionType", is("COMMENT_UPDATED")));
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(recruiter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(1)));
        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(application.getId()).orElseThrow().getStatus());
    }

    @Test
    @Timeout(45)
    void hrManagerOfAnotherOrganisationSeesTheJobAsMissing() throws Exception {
        var principal = user(otherHrEmail).roles("HR_MANAGER");
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), application.getId())
                        .with(principal).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"review\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(principal))
                .andExpect(status().isNotFound());

        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(application.getId()).orElseThrow().getStatus());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());
    }

    @Test
    @Timeout(45)
    void anyHrManagerOfTheOrganisationManagesTheJob() throws Exception {
        var colleague = user(colleagueHrEmail).roles("HR_MANAGER");
        mvc.perform(post(REVIEW_ROUTE + "/shortlist", job.getId(), application.getId())
                        .with(colleague).contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Colleague decision\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.newStatus", is("SHORTLISTED")));
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(colleague))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[1].actionType", is("IDENTITY_REVEALED")));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(3)));

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

            Assertions.assertThrows(IllegalStateException.class, () -> decisionService.decide(job.getId(),
                    application.getId(), new DecisionRequest(ApplicationDecisionAction.SHORTLIST, "must roll back")));
        } finally {
            reset(history);
            SecurityContextHolder.clearContext();
        }

        assertTrue(auditInsertFlushed.get(), "the audit insert must have flushed before the synthetic failure");
        Application afterFailure = applications.findById(application.getId()).orElseThrow();
        assertEquals(ApplicationStatus.SUBMITTED, afterFailure.getStatus());
        assertNull(afterFailure.getReviewComment());
        assertNull(afterFailure.getIdentityRevealedAt());
        assertNull(afterFailure.getStatusChangedAt());
        assertEquals(0, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).size());
    }

    @Test
    @Timeout(45)
    void concurrentHrDecisionAndCandidateWithdrawalCommitOnlyOneConsistentOutcome() throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> decision = workers.submit(concurrentRequest(ownerEmail, "HR_MANAGER", start,
                    () -> decisionService.decide(job.getId(), application.getId(),
                            new DecisionRequest(ApplicationDecisionAction.SHORTLIST, "synthetic HR decision"))));
            Future<Boolean> withdrawal = workers.submit(concurrentRequest(candidate.getUser().getEmail(), "CANDIDATE", start,
                    () -> applicationService.withdraw(application.getId())));
            start.await(10, TimeUnit.SECONDS);

            boolean decisionSucceeded = decision.get(20, TimeUnit.SECONDS);
            boolean withdrawalSucceeded = withdrawal.get(20, TimeUnit.SECONDS);
            assertTrue(withdrawalSucceeded, "a candidate may withdraw a submitted or shortlisted application");

            // The row lock serialises the two transitions: either SUBMITTED -> SHORTLISTED -> WITHDRAWN,
            // or SUBMITTED -> WITHDRAWN and the shortlist is rejected; never a lost update.
            Application persisted = applications.findById(application.getId()).orElseThrow();
            List<ApplicationDecisionHistory> events = history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId());
            assertEquals(ApplicationStatus.WITHDRAWN, persisted.getStatus());
            if (decisionSucceeded) {
                assertEquals("synthetic HR decision", persisted.getReviewComment());
                assertEquals(List.of(ApplicationDecisionAction.SHORTLIST, ApplicationDecisionAction.IDENTITY_REVEALED,
                        ApplicationDecisionAction.WITHDRAW), events.stream().map(ApplicationDecisionHistory::getActionType).toList());
                assertEquals(ApplicationStatus.SHORTLISTED, events.get(2).getPreviousStatus());
            } else {
                assertNull(persisted.getReviewComment());
                assertEquals(1, events.size());
                assertEquals(ApplicationDecisionAction.WITHDRAW, events.get(0).getActionType());
                assertEquals(ApplicationStatus.SUBMITTED, events.get(0).getPreviousStatus());
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

    private HRProfile createHr(Organization owningOrganization, String email, Role role) {
        HRProfile profile = new HRProfile();
        profile.setUser(fixtures.staff(owningOrganization, email, role));
        return hrProfiles.save(profile);
    }

    private User createUser(String email, Role role) {
        return users.save(User.builder().email(email).passwordHash("test-only").firstName("Test")
                .lastName("User").role(role).enabled(true).build());
    }

    private Job createJob(HRProfile owner) {
        Job created = new Job();
        created.setCreatedBy(owner);
        created.setOrganizationId(organization.getId());
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

        // 7. Recruiter of the organisation can also list
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user(recruiterEmail).roles("RECRUITER")))
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

        // HR of another organisation -> 404 (foreign jobs behave as missing)
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(user(otherHrEmail).roles("HR_MANAGER")))
                .andExpect(status().isNotFound());
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

        // 3. Reject a SHORTLISTED application -> OK (plan 3.4); rejecting again -> 400
        mvc.perform(post(REVIEW_ROUTE + "/reject", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
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

        // 5. Comments are notes and are allowed before any decision (plan 5.4)
        CandidateProfile candSubmitted = createCandidate("review-submitted-app-" + UUID.randomUUID() + "@example.test");
        Application submittedApp = createApplication(candSubmitted, job);

        mvc.perform(put(REVIEW_ROUTE + "/review-comment", job.getId(), submittedApp.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Early comment\"}"))
                .andExpect(status().isOk());

        // 6. Undo and reopen need a reason
        mvc.perform(post(REVIEW_ROUTE + "/decisions", job.getId(), application.getId())
                        .with(ownerPrincipal).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REOPEN\"}"))
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

        // Size > 100 -> 400
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .param("size", "101").with(ownerPrincipal))
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
                .andExpect(jsonPath("$.data.content[0].verifiedCoverage").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].matchStatus", is("NO_REQUIREMENTS")))
                .andExpect(jsonPath("$.data.content[0].rankGroup", is("UNSCORED")));

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

        // Audit history reflects human actor and timestamp (plus the automatic identity reveal, D4)
        mvc.perform(get(REVIEW_ROUTE + "/history", job.getId(), application.getId()).with(ownerPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[0].actionType", is("SHORTLIST")))
                .andExpect(jsonPath("$.data[0].actorName", is("Test User")))
                .andExpect(jsonPath("$.data[0].comment", is(commentText)));
    }
}
