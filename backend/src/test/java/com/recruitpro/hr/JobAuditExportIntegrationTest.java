package com.recruitpro.hr;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.audit.entity.AuditEvent;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/hr/jobs/{jobId}/audit-export: one CSV row per decision with the match run it was made on, spreadsheet
 * formulas neutralised, candidate actors without identity, audited as AUDIT_EXPORTED, managers only, tenancy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobAuditExportIntegrationTest {

    private static final String EXPORT = "/api/v1/hr/jobs/{jobId}/audit-export";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationDecisionHistoryRepository history;
    @Autowired AuditEventRepository auditEvents;

    Organization orgA;
    Job job;
    Application application;
    User managerUser;
    JobMatchRun run;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        orgA = fixtures.organization("Export Org A");
        Organization orgB = fixtures.organization("Export Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@exp-a.test", Role.HR_MANAGER);
        managerUser = manager.getUser();
        fixtures.staff(orgA, "recruiter@exp-a.test", Role.RECRUITER);
        fixtures.hrMember(orgB, "manager@exp-b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Controller", JobStatus.PUBLISHED);
        JobRequirement requirement = screening.requirement(job, RequirementType.SKILL, "Excel", true, true);
        CandidateProfile candidate = fixtures.candidate("person@exp-cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        application = screening.application(candidate, job, resume, UtcTime.now().minusDays(2));
        run = screening.currentRun(application, analysis, 87.5, 90.0, true,
                List.of(ScreeningFixtures.matched(requirement.getId(), "RESUME_STATED", "RESUME")), List.of());
        event(ApplicationStatus.SUBMITTED, ApplicationStatus.SHORTLISTED, ApplicationDecisionAction.SHORTLIST, managerUser,
                "=cmd|' /C calc'!A0", run.getId());
        event(ApplicationStatus.SHORTLISTED, ApplicationStatus.WITHDRAWN, ApplicationDecisionAction.WITHDRAW, candidate.getUser(),
                null, run.getId());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void exportsEveryDecisionWithItsMatchRunSafelyAndIsAudited() throws Exception {
        MvcResult result = mvc.perform(get(EXPORT, job.getId()).with(staff("manager@exp-a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("job-" + job.getId() + "-audit-")))
                .andReturn();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length, "header plus one row per decision");
        assertTrue(lines[0].startsWith("decisionId,timestamp,applicationId,actorUserId,actorRole,actorName,actorEmail,action,"
                + "previousStatus,newStatus,comment,matchRunId,algorithmVersion,verifiedCoverage,claimedCoverage,knockoutsVerified"));
        String shortlist = lines[1];
        assertTrue(shortlist.contains("\"SHORTLIST\""));
        assertTrue(shortlist.contains("\"manager@exp-a.test\""));
        assertTrue(shortlist.contains("\"'=cmd|' /C calc'!A0\""), "formulas are neutralised with an apostrophe");
        assertFalse(shortlist.contains(",=cmd"), "no cell may start with a formula character");
        assertTrue(shortlist.endsWith("," + run.getId() + ",\"evidence-ladder-3\",87.5,90.0,true"));
        String withdraw = lines[2];
        assertTrue(withdraw.contains("\"WITHDRAW\""));
        assertTrue(withdraw.contains("\"Candidate\""));
        assertFalse(csv.contains("person@exp-cand.test"), "candidate identities are not exported");

        List<AuditEvent> exports = auditEvents.findAllByActionOrderByIdAsc(AuditActions.AUDIT_EXPORTED);
        assertEquals(1, exports.size());
        assertEquals(orgA.getId(), exports.get(0).getOrganizationId());
        assertEquals("JOB", exports.get(0).getTargetType());
        assertEquals(String.valueOf(job.getId()), exports.get(0).getTargetId());
    }

    @Test
    void onlyManagersOfTheOrganisationMayExport() throws Exception {
        mvc.perform(get(EXPORT, job.getId()).with(staff("recruiter@exp-a.test", "RECRUITER"))).andExpect(status().isForbidden());
        mvc.perform(get(EXPORT, job.getId()).with(staff("manager@exp-b.test", "HR_MANAGER"))).andExpect(status().isNotFound());
        mvc.perform(get(EXPORT, job.getId()).with(SecurityMockMvcRequestPostProcessors.user("person@exp-cand.test").roles("CANDIDATE")))
                .andExpect(status().isForbidden());
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(AuditActions.AUDIT_EXPORTED).isEmpty());
    }

    private void event(ApplicationStatus from, ApplicationStatus to, ApplicationDecisionAction action, User actor,
                       String comment, Long matchRunId) {
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(application);
        event.setPreviousStatus(from);
        event.setNewStatus(to);
        event.setActionType(action);
        event.setActor(actor);
        event.setComment(comment);
        event.setMatchRunId(matchRunId);
        history.saveAndFlush(event);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
