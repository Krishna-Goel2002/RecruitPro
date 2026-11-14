package com.recruitpro.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HR applicant review (plan 5.4): server-side ranking across pages, blind review and reveal (D4), staff resume
 * download, reversible decisions, claim verification, rematch, interviewers and tenancy isolation (D3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicantReviewIntegrationTest {

    private static final String APPLICANTS = "/api/v1/hr/jobs/{jobId}/applicants";
    private static final String APPLICANT = "/api/v1/hr/jobs/{jobId}/applicants/{applicationId}";
    private static final LocalDateTime T0 = LocalDateTime.parse("2026-10-01T09:00:00");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired OrganizationRepository organizations;
    @Autowired CandidateClaimRepository claims;
    @Autowired AuditEventRepository auditEvents;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobRepository backgroundJobs;
    @Autowired BackgroundJobService backgroundJobService;
    @Autowired ResumeStorage resumeStorage;
    @Autowired JdbcTemplate jdbcTemplate;

    Organization orgA;
    HRProfile managerB;
    Job job;
    JobRequirement java;
    JobRequirement docker;
    Application a1, a2, a3, a4, a5;
    JobMatchRun run2;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        orgA = fixtures.organization("Review Org A");
        Organization orgB = fixtures.organization("Review Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "admin@a.test", Role.ADMIN);
        fixtures.staff(orgA, "recruiter@a.test", Role.RECRUITER);
        fixtures.staff(orgA, "interviewer@a.test", Role.INTERVIEWER);
        managerB = fixtures.hrMember(orgB, "manager@b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        docker = screening.requirement(job, RequirementType.SKILL, "Docker", false, false);

        a1 = applicant("one@cand.test", 0, 50.0, 50.0, true);
        a2 = applicant("two@cand.test", 1, 90.0, 90.0, true);
        a3 = applicant("three@cand.test", 2, 95.0, 95.0, false);
        a4 = applicant("four@cand.test", 3, null, null, null);
        a5 = applicant("five@cand.test", 4, 90.0, 100.0, true);
        backgroundJobService.enqueue(BackgroundJobType.MATCH_APPLICATION, a4.getId());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void rankingSpansEveryPageAndIdentitiesStayHidden() throws Exception {
        var recruiter = staff("recruiter@a.test", "RECRUITER");
        MvcResult first = mvc.perform(get(APPLICANTS, job.getId()).param("size", "2").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(5)))
                .andExpect(jsonPath("$.data.totalPages", is(3)))
                .andExpect(jsonPath("$.data.first", is(true)))
                .andExpect(jsonPath("$.data.last", is(false)))
                .andExpect(jsonPath("$.data.content[0].applicationId", is(a5.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].rank", is(1)))
                .andExpect(jsonPath("$.data.content[0].rankGroup", is("RANKED")))
                .andExpect(jsonPath("$.data.content[0].verifiedCoverage", is(90.0)))
                .andExpect(jsonPath("$.data.content[0].claimedCoverage", is(100.0)))
                .andExpect(jsonPath("$.data.content[0].matchStatus", is("READY")))
                .andExpect(jsonPath("$.data.content[0].displayName", is("Candidate #A-" + a5.getId())))
                .andExpect(jsonPath("$.data.content[0].identityRevealed", is(false)))
                .andExpect(jsonPath("$.data.content[1].applicationId", is(a2.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].rank", is(2)))
                .andReturn();
        String body = first.getResponse().getContentAsString();
        assertFalse(body.contains("five@cand.test") || body.contains("Test Candidate"), "blind review hides identities");

        mvc.perform(get(APPLICANTS, job.getId()).param("size", "2").param("page", "1").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].applicationId", is(a1.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].rank", is(3)))
                .andExpect(jsonPath("$.data.content[1].applicationId", is(a3.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].rankGroup", is("MUST_HAVES_UNVERIFIED")))
                .andExpect(jsonPath("$.data.content[1].rank").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].knockoutsVerified", is(false)))
                .andExpect(jsonPath("$.data.content[1].unverifiedKnockouts[0]", is(java.getId().intValue())));
        mvc.perform(get(APPLICANTS, job.getId()).param("size", "2").param("page", "2").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.last", is(true)))
                .andExpect(jsonPath("$.data.content.length()", is(1)))
                .andExpect(jsonPath("$.data.content[0].applicationId", is(a4.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].rankGroup", is("UNSCORED")))
                .andExpect(jsonPath("$.data.content[0].matchStatus", is("PENDING")))
                .andExpect(jsonPath("$.data.content[0].analysisStatus", is("COMPLETED")));

        mvc.perform(get(APPLICANTS, job.getId()).param("sort", "appliedAt").param("size", "2").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].applicationId", is(a5.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].applicationId", is(a4.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].rankGroup", is("UNSCORED")));

        screening.withStatus(a2, ApplicationStatus.REJECTED);
        mvc.perform(get(APPLICANTS, job.getId()).param("status", "SUBMITTED").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(4)))
                .andExpect(jsonPath("$.data.content[0].applicationId", is(a5.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].applicationId", is(a1.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].rank", is(2)));

        mvc.perform(get(APPLICANTS, job.getId()).param("size", "101").with(recruiter)).andExpect(status().isBadRequest());
        mvc.perform(get(APPLICANTS, job.getId()).param("sort", "score").with(recruiter)).andExpect(status().isBadRequest());
    }

    @Test
    void shortlistingRevealsTheIdentityRecordsTheMatchRunAndNotifiesTheCandidate() throws Exception {
        var manager = staff("manager@a.test", "HR_MANAGER");
        mvc.perform(get(APPLICANT, job.getId(), a2.getId()).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName", is("Candidate #A-" + a2.getId())))
                .andExpect(jsonPath("$.data.candidate").doesNotExist())
                .andExpect(jsonPath("$.data.coverLetter").doesNotExist())
                .andExpect(jsonPath("$.data.resumeAvailable", is(false)))
                .andExpect(jsonPath("$.data.resumeFilename").doesNotExist())
                .andExpect(jsonPath("$.data.matchRunId", is(run2.getId().intValue())))
                .andExpect(jsonPath("$.data.requirements.length()", is(2)))
                .andExpect(jsonPath("$.data.requirements[0].requirementId", is(java.getId().intValue())))
                .andExpect(jsonPath("$.data.requirements[0].status", is("MATCHED")))
                .andExpect(jsonPath("$.data.requirements[0].tier", is("RESUME_STATED")))
                .andExpect(jsonPath("$.data.requirements[0].knockout", is(true)))
                .andExpect(jsonPath("$.data.allowedActions", contains("SHORTLIST", "REJECT")))
                .andExpect(jsonPath("$.data.revealAllowed", is(false)))
                .andExpect(jsonPath("$.data.workSamples.length()", is(0)))
                .andExpect(jsonPath("$.data.interviews.length()", is(0)));

        mvc.perform(post(APPLICANT + "/decisions", job.getId(), a2.getId()).with(manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHORTLIST\",\"comment\":\"Strong evidence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("SHORTLISTED")))
                .andExpect(jsonPath("$.data.actionType", is("SHORTLIST")))
                .andExpect(jsonPath("$.data.matchRunId", is(run2.getId().intValue())));

        mvc.perform(get(APPLICANT, job.getId(), a2.getId()).with(staff("recruiter@a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityRevealed", is(true)))
                .andExpect(jsonPath("$.data.displayName", is("Test Candidate")))
                .andExpect(jsonPath("$.data.candidate.email", is("two@cand.test")))
                .andExpect(jsonPath("$.data.coverLetter", is("Cover letter of two@cand.test")))
                .andExpect(jsonPath("$.data.resumeAvailable", is(true)))
                .andExpect(jsonPath("$.data.history[*].actionType", contains("SHORTLIST", "IDENTITY_REVEALED")))
                .andExpect(jsonPath("$.data.allowedActions.length()", is(0)));

        Application stored = applications.findById(a2.getId()).orElseThrow();
        assertNotNull(stored.getIdentityRevealedAt());
        assertNotNull(stored.getStatusChangedAt());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(), AuditActions.IDENTITY_REVEALED).size());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(), AuditActions.APPLICATION_DECISION).size());
        assertFalse(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("two@cand.test",
                NotificationTemplate.APPLICATION_STATUS_CHANGED).isEmpty());

        mvc.perform(post(APPLICANT + "/shortlist", job.getId(), a5.getId()).with(staff("admin@a.test", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.newStatus", is("SHORTLISTED")));
        mvc.perform(post(APPLICANT + "/reject", job.getId(), a1.getId()).with(manager))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.newStatus", is("REJECTED")));
        assertNull(applications.findById(a1.getId()).orElseThrow().getIdentityRevealedAt(), "rejection does not reveal");
    }

    @Test
    void revealAndStaffResumeDownloadFollowBlindReviewAndAreAudited() throws Exception {
        var manager = staff("manager@a.test", "HR_MANAGER");
        var recruiter = staff("recruiter@a.test", "RECRUITER");
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a1.getId()).with(manager)).andExpect(status().isBadRequest());
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a1.getId()).with(recruiter)).andExpect(status().isForbidden());
        mvc.perform(get(APPLICANT + "/resume", job.getId(), a1.getId()).with(manager)).andExpect(status().isForbidden());
        assertTrue(auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(), AuditActions.RESUME_DOWNLOADED_BY_STAFF).isEmpty());

        mvc.perform(post(APPLICANT + "/decisions", job.getId(), a1.getId()).with(manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(APPLICANT, job.getId(), a1.getId()).with(manager))
                .andExpect(jsonPath("$.data.revealAllowed", is(true)));
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a1.getId()).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityRevealed", is(true)))
                .andExpect(jsonPath("$.data.candidate.fullName", is("Test Candidate")))
                .andExpect(jsonPath("$.data.revealAllowed", is(false)));
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a1.getId()).with(manager)).andExpect(status().isOk());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(), AuditActions.IDENTITY_REVEALED).size(),
                "revealing twice is idempotent");

        Resume resume = applications.findAllWithCandidateByIdIn(List.of(a1.getId())).get(0).getResume();
        byte[] pdf = "%PDF-1.7\nresume of candidate one\n%%EOF".getBytes();
        resumeStorage.store(resume.getStorageKey(), pdf);
        try {
            MvcResult download = mvc.perform(get(APPLICANT + "/resume", job.getId(), a1.getId()).with(recruiter))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition", containsString("application-" + a1.getId() + "-resume.pdf")))
                    .andReturn();
            assertArrayEquals(pdf, download.getResponse().getContentAsByteArray());
            assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(),
                    AuditActions.RESUME_DOWNLOADED_BY_STAFF).size());
        } finally {
            resumeStorage.delete(resume.getStorageKey());
        }

        orgA.setBlindReview(false);
        organizations.save(orgA);
        mvc.perform(get(APPLICANT, job.getId(), a4.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityRevealed", is(true)))
                .andExpect(jsonPath("$.data.displayName", is("Test Candidate")))
                .andExpect(jsonPath("$.data.candidate.email", is("four@cand.test")));
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a4.getId()).with(manager)).andExpect(status().isOk());
    }

    @Test
    void decisionsAreReversibleWithCommentsAndFollowTheStateMachine() throws Exception {
        var manager = staff("manager@a.test", "HR_MANAGER");
        var recruiter = staff("recruiter@a.test", "RECRUITER");
        decide(manager, a5, "SELECT", null).andExpect(status().isBadRequest());
        decide(manager, a5, "COMMENT_UPDATED", null).andExpect(status().isBadRequest());
        decide(recruiter, a5, "SHORTLIST", null).andExpect(status().isForbidden());
        mvc.perform(post(APPLICANT + "/decisions", job.getId(), a5.getId()).with(manager)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());

        decide(manager, a5, "SHORTLIST", null).andExpect(status().isOk());
        decide(manager, a5, "UNDO_SHORTLIST", null).andExpect(status().isBadRequest());
        decide(manager, a5, "UNDO_SHORTLIST", "Shortlisted by mistake").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("SUBMITTED")));
        assertNotNull(applications.findById(a5.getId()).orElseThrow().getIdentityRevealedAt(), "identity stays revealed");

        decide(manager, a5, "REJECT", null).andExpect(status().isOk());
        decide(manager, a5, "REOPEN", " ").andExpect(status().isBadRequest());
        decide(manager, a5, "REOPEN", "Hiring manager asked to reconsider").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("SUBMITTED")));

        decide(manager, a5, "SHORTLIST", null).andExpect(status().isOk());
        decide(manager, a5, "MOVE_TO_INTERVIEW", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("INTERVIEWING")));
        mvc.perform(get(APPLICANT, job.getId(), a5.getId()).with(manager))
                .andExpect(jsonPath("$.data.allowedActions", contains("SELECT", "REJECT")));
        decide(manager, a5, "SELECT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus", is("SELECTED")));
        decide(manager, a5, "REJECT", null).andExpect(status().isBadRequest());

        mvc.perform(put(APPLICANT + "/review-comment", job.getId(), a5.getId()).with(recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Recruiter note\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.actionType", is("COMMENT_UPDATED")));
        mvc.perform(get(APPLICANT + "/history", job.getId(), a5.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].actionType", contains("SHORTLIST", "IDENTITY_REVEALED", "UNDO_SHORTLIST",
                        "REJECT", "REOPEN", "SHORTLIST", "MOVE_TO_INTERVIEW", "SELECT", "COMMENT_UPDATED")));
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", a5.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user("five@cand.test").roles("CANDIDATE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recruitersVerifyClaimsWhichIsAuditedAndRequeuesMatching() throws Exception {
        Application loaded = applications.findAllWithCandidateByIdIn(List.of(a1.getId())).get(0);
        CandidateProfile candidate = loaded.getCandidate();
        Job jobB = fixtures.job(managerB, "Role at another organisation", JobStatus.PUBLISHED);
        Application atOrgB = screening.application(candidate, jobB, loaded.getResume(), T0, null);
        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(candidate);
        claim.setClaimType(ClaimType.CERTIFICATION);
        claim.setClaimValue("AWS Solutions Architect");
        claim.setEvidenceUrl("https://credentials.example.test/one");
        claim = claims.save(claim);
        CandidateProfile other = applications.findAllWithCandidateByIdIn(List.of(a2.getId())).get(0).getCandidate();
        CandidateClaim foreign = new CandidateClaim();
        foreign.setCandidate(other);
        foreign.setClaimType(ClaimType.SKILL);
        foreign.setClaimValue("Go");
        foreign = claims.save(foreign);

        var recruiter = staff("recruiter@a.test", "RECRUITER");
        mvc.perform(get(APPLICANT, job.getId(), a1.getId()).with(recruiter))
                .andExpect(jsonPath("$.data.claims[0].value", is("AWS Solutions Architect")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.claims[0].hasEvidenceUrl", is(true)))
                .andExpect(jsonPath("$.data.claims[0].evidenceUrl").doesNotExist());

        mvc.perform(post(APPLICANT + "/claims/{claimId}/verification", job.getId(), a1.getId(), claim.getId()).with(recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true,\"note\":\"Checked the certificate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier", is("CORROBORATED")))
                .andExpect(jsonPath("$.data.verificationStatus", is("VERIFIED")))
                .andExpect(jsonPath("$.data.verificationNote", is("Checked the certificate")));
        assertEquals(EvidenceTier.CORROBORATED, claims.findById(claim.getId()).orElseThrow().getTier());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(orgA.getId(), AuditActions.CLAIM_VERIFIED).size());
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, a1.getId()).stream()
                .anyMatch(j -> j.getStatus() == BackgroundJobStatus.PENDING), "verification re-queues matching");
        // D3: the verification counts only inside organisation A and is not disclosed to organisation B.
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, atOrgB.getId()).isEmpty());
        mvc.perform(get(APPLICANT, jobB.getId(), atOrgB.getId()).with(staff("manager@b.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].tier", contains("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verificationStatus", contains("UNVERIFIED")))
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verifiedAt").isEmpty());

        mvc.perform(post(APPLICANT + "/claims/{claimId}/verification", job.getId(), a1.getId(), claim.getId()).with(recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.verificationStatus", is("REJECTED")));
        mvc.perform(post(APPLICANT + "/claims/{claimId}/verification", job.getId(), a1.getId(), foreign.getId()).with(recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(APPLICANT + "/claims/{claimId}/verification", job.getId(), a1.getId(), claim.getId()).with(recruiter)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rematchQueuesEveryOpenApplicationAndInterviewersAreOrganisationScoped() throws Exception {
        screening.withStatus(a3, ApplicationStatus.WITHDRAWN);
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/rematch", job.getId()).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enqueued", is(4)));
        for (Application application : List.of(a1, a2, a4, a5)) {
            assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, application.getId())
                    .stream().anyMatch(j -> j.getStatus() == BackgroundJobStatus.PENDING));
        }
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, a3.getId()).isEmpty());
        assertEquals(1, backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, a4.getId()).size(),
                "an application already waiting is not queued twice");
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/rematch", job.getId()).with(staff("recruiter@a.test", "RECRUITER")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/hr/interviewers").with(staff("interviewer@a.test", "INTERVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(4)))
                .andExpect(jsonPath("$.data[*].email", containsInAnyOrder("manager@a.test", "admin@a.test",
                        "recruiter@a.test", "interviewer@a.test")));
        mvc.perform(get("/api/v1/hr/interviewers").with(SecurityMockMvcRequestPostProcessors.user("one@cand.test").roles("CANDIDATE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyApplicantEndpointTreatsAnotherOrganisationsJobAsMissing() throws Exception {
        var other = staff("manager@b.test", "HR_MANAGER");
        mvc.perform(get(APPLICANTS, job.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(APPLICANT, job.getId(), a1.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(APPLICANT + "/history", job.getId(), a1.getId()).with(other)).andExpect(status().isNotFound());
        decide(other, a1, "SHORTLIST", null).andExpect(status().isNotFound());
        mvc.perform(post(APPLICANT + "/reveal", job.getId(), a1.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(APPLICANT + "/resume", job.getId(), a1.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(put(APPLICANT + "/review-comment", job.getId(), a1.getId()).with(other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post(APPLICANT + "/claims/{claimId}/verification", job.getId(), a1.getId(), 1L).with(other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/rematch", job.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/hr/interviewers").with(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].email", contains("manager@b.test")));
        mvc.perform(get(APPLICANTS, job.getId()).with(SecurityMockMvcRequestPostProcessors.user("one@cand.test").roles("CANDIDATE")))
                .andExpect(status().isForbidden());
        mvc.perform(get(APPLICANTS, job.getId()).with(staff("interviewer@a.test", "INTERVIEWER")))
                .andExpect(status().isForbidden());
        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(a1.getId()).orElseThrow().getStatus());
    }

    @Test
    void applicantGetsNeverWrite() throws Exception {
        Integer runsBefore = count("job_match_runs");
        Integer jobsBefore = count("background_jobs");
        Integer auditBefore = count("audit_events");
        var manager = staff("manager@a.test", "HR_MANAGER");
        mvc.perform(get(APPLICANTS, job.getId()).with(manager)).andExpect(status().isOk());
        mvc.perform(get(APPLICANT, job.getId(), a4.getId()).with(manager)).andExpect(status().isOk());
        mvc.perform(get(APPLICANT + "/history", job.getId(), a4.getId()).with(manager)).andExpect(status().isOk());
        assertEquals(runsBefore, count("job_match_runs"));
        assertEquals(jobsBefore, count("background_jobs"));
        assertEquals(auditBefore, count("audit_events"));
    }

    private Integer count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private org.springframework.test.web.servlet.ResultActions decide(
            SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor principal, Application application,
            String action, String comment) throws Exception {
        String body = comment == null ? "{\"action\":\"" + action + "\"}"
                : "{\"action\":\"" + action + "\",\"comment\":" + mapper.writeValueAsString(comment) + "}";
        return mvc.perform(post(APPLICANT + "/decisions", job.getId(), application.getId()).with(principal)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Application applicant(String email, int minutes, Double verified, Double claimed, Boolean knockoutsVerified) {
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, email.replace("@", "-") + ".pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, job, resume, T0.plusMinutes(minutes), "Cover letter of " + email);
        if (verified != null) {
            List<Long> unverified = Boolean.TRUE.equals(knockoutsVerified) ? List.of() : List.of(java.getId());
            JobMatchRun run = screening.currentRun(application, analysis, verified, claimed, knockoutsVerified,
                    List.of(Boolean.TRUE.equals(knockoutsVerified)
                                    ? ScreeningFixtures.matched(java.getId(), "RESUME_STATED", "RESUME")
                                    : ScreeningFixtures.notObserved(java.getId()),
                            ScreeningFixtures.matched(docker.getId(), "SELF_CLAIMED", "CANDIDATE_CLAIM")),
                    unverified);
            if ("two@cand.test".equals(email)) {
                run2 = run;
            }
        }
        return application;
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

    @SuppressWarnings("unused")
    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
