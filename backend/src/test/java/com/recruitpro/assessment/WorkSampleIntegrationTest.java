package com.recruitpro.assessment;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.assessment.service.WorkSampleEvidenceContributor;
import com.recruitpro.assessment.service.WorkSampleExpirySweeper;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Work samples (plan 5.6, WP-BE-C): editing and activation, assignment rules, the candidate's timed flow with the
 * 5-minute grace, expiry, scoring (DEMONSTRATED evidence for requirement-linked criteria scored 3+), what the
 * candidate can and cannot see, and tenancy isolation on every endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkSampleIntegrationTest {

    private static final String SAMPLES = "/api/v1/hr/jobs/{jobId}/work-samples";
    private static final String SAMPLE = "/api/v1/hr/jobs/{jobId}/work-samples/{id}";
    private static final String ASSIGN = "/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/work-samples";
    private static final String ASSIGNMENTS = "/api/v1/hr/jobs/{jobId}/work-sample-assignments";
    private static final String SCORE = "/api/v1/hr/work-sample-assignments/{id}/score";
    private static final String MINE = "/api/v1/candidates/me/work-samples";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired WorkSampleRepository workSamples;
    @Autowired WorkSampleAssignmentRepository assignments;
    @Autowired AuditEventRepository auditEvents;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobService backgroundJobService;
    @Autowired MatchingService matchingService;
    @Autowired WorkSampleExpirySweeper sweeper;

    Job job;
    Job otherJob;
    JobRequirement java;
    JobRequirement docker;
    JobRequirement otherRequirement;
    Application shortlisted;
    Application interviewing;
    Application submitted;
    WorkSample active;
    WorkSample active2;
    WorkSample draft;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization orgA = fixtures.organization("Samples Org A");
        Organization orgB = fixtures.organization("Samples Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@ws-a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "recruiter@ws-a.test", Role.RECRUITER);
        fixtures.staff(orgA, "interviewer@ws-a.test", Role.INTERVIEWER);
        HRProfile managerB = fixtures.hrMember(orgB, "manager@ws-b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
        otherJob = fixtures.job(managerB, "Other Org Job", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        docker = screening.requirement(job, RequirementType.SKILL, "Docker", false, false);
        otherRequirement = screening.requirement(otherJob, RequirementType.SKILL, "Go", true, true);
        shortlisted = application("short@ws-cand.test", ApplicationStatus.SHORTLISTED);
        interviewing = application("inter@ws-cand.test", ApplicationStatus.INTERVIEWING);
        submitted = application("sub@ws-cand.test", ApplicationStatus.SUBMITTED);
        active = sample(job, "Payments API task", WorkSampleStatus.ACTIVE, java.getId());
        active2 = sample(job, "Second task", WorkSampleStatus.ACTIVE, java.getId());
        draft = sample(job, "Draft task", WorkSampleStatus.DRAFT, null);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void draftsAreEditableAndActivationLocksThem() throws Exception {
        String body = "{\"title\":\"Edited task\",\"instructions\":\"Write a parser\",\"timeLimitMinutes\":40,\"rubric\":["
                + criterion(docker.getId(), "Container skills") + "]}";
        mvc.perform(put(SAMPLE, job.getId(), draft.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title", is("Edited task")))
                .andExpect(jsonPath("$.data.timeLimitMinutes", is(40)))
                .andExpect(jsonPath("$.data.rubric[0].requirementId", is(docker.getId().intValue())))
                .andExpect(jsonPath("$.data.rubric[0].anchors['4']", is("excellent")))
                .andExpect(jsonPath("$.data.status", is("DRAFT")));

        String foreign = "{\"title\":\"x\",\"instructions\":\"y\",\"timeLimitMinutes\":40,\"rubric\":["
                + criterion(otherRequirement.getId(), "Go skills") + "]}";
        mvc.perform(put(SAMPLE, job.getId(), draft.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(foreign)).andExpect(status().isBadRequest());
        String missingAnchor = "{\"title\":\"x\",\"instructions\":\"y\",\"timeLimitMinutes\":40,\"rubric\":["
                + "{\"requirementId\":null,\"criterion\":\"Clarity\",\"anchors\":{\"1\":\"a\",\"2\":\"b\",\"3\":\"c\"}}]}";
        mvc.perform(put(SAMPLE, job.getId(), draft.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(missingAnchor)).andExpect(status().isBadRequest());
        mvc.perform(put(SAMPLE, job.getId(), active.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put(SAMPLE, job.getId(), draft.getId()).with(staff("recruiter@ws-a.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        mvc.perform(patch(SAMPLE + "/status", job.getId(), draft.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ACTIVE")));
        mvc.perform(get(SAMPLES, job.getId()).with(staff("recruiter@ws-a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(3)));
        mvc.perform(get(SAMPLES, job.getId()).with(staff("interviewer@ws-a.test", "INTERVIEWER")))
                .andExpect(status().isForbidden());

        assignment(active, shortlisted, WorkSampleAssignmentStatus.ASSIGNED, null, null);
        mvc.perform(patch(SAMPLE + "/status", job.getId(), active.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch(SAMPLE + "/status", job.getId(), active.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ARCHIVED")));

        // Tenancy: another organisation's manager sees nothing of this job.
        var other = staff("manager@ws-b.test", "HR_MANAGER");
        mvc.perform(get(SAMPLES, job.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(put(SAMPLE, job.getId(), draft.getId()).with(other)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(patch(SAMPLE + "/status", job.getId(), draft.getId()).with(other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isNotFound());
        mvc.perform(post(SAMPLES + "/generate", job.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(ASSIGNMENTS, job.getId()).with(other)).andExpect(status().isNotFound());
        // The same organisation's job with a sample id of another job is 404 too.
        WorkSample foreignSample = sample(otherJob, "Foreign task", WorkSampleStatus.DRAFT, null);
        mvc.perform(put(SAMPLE, job.getId(), foreignSample.getId()).with(staff("manager@ws-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
    }

    @Test
    void assignmentNeedsAnActiveSampleAndAShortlistedOrInterviewingApplication() throws Exception {
        var manager = staff("manager@ws-a.test", "HR_MANAGER");
        String future = UtcTime.format(UtcTime.now().plusDays(3));
        mvc.perform(assign(manager, shortlisted, draft, null)).andExpect(status().isBadRequest());
        mvc.perform(assign(manager, submitted, active, null)).andExpect(status().isBadRequest());
        mvc.perform(assign(manager, shortlisted, active, UtcTime.format(UtcTime.now().minusHours(1)))).andExpect(status().isBadRequest());
        mvc.perform(assign(staff("recruiter@ws-a.test", "RECRUITER"), shortlisted, active, null)).andExpect(status().isForbidden());
        mvc.perform(assign(staff("manager@ws-b.test", "HR_MANAGER"), shortlisted, active, null)).andExpect(status().isNotFound());
        WorkSample foreignSample = sample(otherJob, "Foreign task", WorkSampleStatus.ACTIVE, null);
        mvc.perform(assign(manager, shortlisted, foreignSample, null)).andExpect(status().isNotFound());

        mvc.perform(assign(manager, shortlisted, active, future))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ASSIGNED")))
                .andExpect(jsonPath("$.data.workSampleTitle", is("Payments API task")))
                .andExpect(jsonPath("$.data.applicationId", is(shortlisted.getId().intValue())))
                .andExpect(jsonPath("$.data.dueAt", notNullValue()))
                .andExpect(jsonPath("$.data.scores.length()", is(0)))
                .andExpect(jsonPath("$.data.totalScore", nullValue()));
        mvc.perform(assign(manager, shortlisted, active, null)).andExpect(status().isConflict());
        mvc.perform(assign(manager, interviewing, active, null)).andExpect(status().isOk());
        assertEquals(1, notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("short@ws-cand.test",
                NotificationTemplate.WORK_SAMPLE_ASSIGNED).stream()
                .filter(n -> n.getChannel() == NotificationChannel.EMAIL).count());

        var recruiter = staff("recruiter@ws-a.test", "RECRUITER");
        mvc.perform(get(ASSIGNMENTS, job.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(2)));
        mvc.perform(get(ASSIGNMENTS, job.getId()).param("status", "SCORED").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(0)));
        mvc.perform(get(ASSIGNMENTS, job.getId()).param("status", "BOGUS").with(recruiter)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).param("status", "SHORTLISTED").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].workSampleStatus", is("ASSIGNED")));
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}", job.getId(), shortlisted.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workSamples.length()", is(1)))
                .andExpect(jsonPath("$.data.workSamples[0].workSampleTitle", is("Payments API task")));
    }

    @Test
    void instructionsStayHiddenUntilStartAndLateSubmissionsAreRejectedAfterTheGrace() throws Exception {
        var candidate = candidate("short@ws-cand.test");
        WorkSampleAssignment onTime = assignment(active, shortlisted, WorkSampleAssignmentStatus.ASSIGNED, null,
                UtcTime.now().plusDays(1));
        WorkSampleAssignment late = assignment(active2, shortlisted, WorkSampleAssignmentStatus.ASSIGNED, null, null);

        String list = mvc.perform(get(MINE).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(2)))
                .andExpect(jsonPath("$.data[0].instructions", nullValue()))
                .andExpect(jsonPath("$.data[0].deadlineAt", nullValue()))
                .andExpect(jsonPath("$.data[0].jobTitle", is("Backend Engineer")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("SECRET-INSTRUCTIONS"), "instructions are hidden until the candidate starts");

        mvc.perform(post(MINE + "/{id}/start", onTime.getId()).with(candidate("sub@ws-cand.test"))).andExpect(status().isNotFound());
        mvc.perform(post(MINE + "/{id}/start", onTime.getId()).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("STARTED")))
                .andExpect(jsonPath("$.data.instructions", containsString("SECRET-INSTRUCTIONS")))
                .andExpect(jsonPath("$.data.deadlineAt", notNullValue()));
        mvc.perform(post(MINE + "/{id}/start", onTime.getId()).with(candidate)).andExpect(status().isOk());

        // 34 minutes after the start of a 30-minute task: inside the 5-minute grace.
        setStartedAt(onTime.getId(), UtcTime.now().minusMinutes(34));
        mvc.perform(post(MINE + "/{id}/submit", onTime.getId()).with(candidate).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseText\":\"My solution\",\"responseUrl\":\"https://git.example.test/repo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.submittedAt", notNullValue()));
        mvc.perform(post(MINE + "/{id}/submit", onTime.getId()).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseText\":\"Again\"}")).andExpect(status().isBadRequest());

        // 36 minutes after the start: past the grace, rejected and marked EXPIRED.
        mvc.perform(post(MINE + "/{id}/submit", late.getId()).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseText\":\"Not started\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(MINE + "/{id}/start", late.getId()).with(candidate)).andExpect(status().isOk());
        setStartedAt(late.getId(), UtcTime.now().minusMinutes(36));
        mvc.perform(post(MINE + "/{id}/submit", late.getId()).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseText\":\"Too late\"}")).andExpect(status().isBadRequest());
        WorkSampleAssignment expired = assignments.findById(late.getId()).orElseThrow();
        assertEquals(WorkSampleAssignmentStatus.EXPIRED, expired.getStatus());
        assertEquals(null, expired.getResponseText());
        mvc.perform(post(MINE + "/{id}/submit", onTime.getId()).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseText\":\"\"}")).andExpect(status().isBadRequest());

        mvc.perform(get(MINE).with(staff("manager@ws-a.test", "HR_MANAGER"))).andExpect(status().isForbidden());
    }

    @Test
    void startIsRefusedAfterTheDueDateAndTheSweepExpiresOverdueAssignments() throws Exception {
        WorkSampleAssignment overdue = assignment(active, shortlisted, WorkSampleAssignmentStatus.ASSIGNED, null,
                UtcTime.now().minusHours(1));
        WorkSampleAssignment running = assignment(active2, shortlisted, WorkSampleAssignmentStatus.STARTED,
                UtcTime.now().minusHours(2), null);
        WorkSampleAssignment fresh = assignment(active, interviewing, WorkSampleAssignmentStatus.ASSIGNED, null,
                UtcTime.now().plusDays(1));
        mvc.perform(post(MINE + "/{id}/start", overdue.getId()).with(candidate("short@ws-cand.test")))
                .andExpect(status().isBadRequest());

        assertEquals(2, sweeper.expireOverdue());
        assertEquals(WorkSampleAssignmentStatus.EXPIRED, assignments.findById(overdue.getId()).orElseThrow().getStatus());
        assertEquals(WorkSampleAssignmentStatus.EXPIRED, assignments.findById(running.getId()).orElseThrow().getStatus());
        assertEquals(WorkSampleAssignmentStatus.ASSIGNED, assignments.findById(fresh.getId()).orElseThrow().getStatus());
        assertEquals(0, sweeper.expireOverdue());
    }

    @Test
    void scoringContributesDemonstratedEvidenceThatTheCandidateCannotSeeInDetail() throws Exception {
        WorkSampleAssignment done = assignment(active, shortlisted, WorkSampleAssignmentStatus.SUBMITTED,
                UtcTime.now().minusMinutes(20), null);
        var recruiter = staff("recruiter@ws-a.test", "RECRUITER");
        mvc.perform(score(recruiter, done, "[" + score("Java design", 4, null) + "]")).andExpect(status().isBadRequest());
        mvc.perform(score(recruiter, done, "[" + score("Java design", 5, null) + "," + score("Communication", 2, null) + "]"))
                .andExpect(status().isBadRequest());
        mvc.perform(score(recruiter, done, "[" + score("Java design", 4, null) + "," + score("Unknown", 2, null) + "]"))
                .andExpect(status().isBadRequest());
        mvc.perform(score(staff("interviewer@ws-a.test", "INTERVIEWER"), done,
                "[" + score("Java design", 4, null) + "," + score("Communication", 2, null) + "]")).andExpect(status().isForbidden());
        mvc.perform(score(staff("manager@ws-b.test", "HR_MANAGER"), done,
                "[" + score("Java design", 4, null) + "," + score("Communication", 2, null) + "]")).andExpect(status().isNotFound());

        mvc.perform(score(recruiter, done, "[" + score("communication", 2, null) + "," + score("Java design", 4, "NOTE-SECRET") + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SCORED")))
                .andExpect(jsonPath("$.data.totalScore", is(3.0)))
                .andExpect(jsonPath("$.data.scoredByName", is("Test User")))
                .andExpect(jsonPath("$.data.scores[0].criterion", is("Java design")))
                .andExpect(jsonPath("$.data.scores[0].requirementId", is(java.getId().intValue())))
                .andExpect(jsonPath("$.data.scores[0].score", is(4)))
                .andExpect(jsonPath("$.data.scores[1].requirementId", nullValue()));
        mvc.perform(score(recruiter, done, "[" + score("Java design", 4, null) + "," + score("Communication", 2, null) + "]"))
                .andExpect(status().isBadRequest());

        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.WORK_SAMPLE_SCORED).size());
        assertFalse(backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, shortlisted.getId()).isEmpty(),
                "scoring queues re-matching");

        PreparedMatch prepared = matchingService.prepare(shortlisted.getId());
        assertNotNull(prepared);
        List<AiMatchRequest.Fact> contributed = prepared.request().facts().stream()
                .filter(fact -> fact.id().startsWith("ws:")).toList();
        assertEquals(1, contributed.size(), "only the requirement-linked criterion scored 3+ becomes evidence");
        AiMatchRequest.Fact fact = contributed.get(0);
        assertEquals(WorkSampleEvidenceContributor.factId(done.getId(), java.getId()), fact.id());
        assertEquals("ws:" + done.getId() + ":r" + java.getId(), fact.id());
        assertEquals("DEMONSTRATED", fact.tier());
        assertEquals("SKILL", fact.type());
        assertEquals("Java", fact.value());
        assertFalse(fact.evidence().contains("short@ws-cand.test"));

        var candidate = candidate("short@ws-cand.test");
        String mine = mvc.perform(get(MINE).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status", is("SCORED")))
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/v1/candidates/me/applications/{id}", shortlisted.getId()).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workSamples.length()", is(1)))
                .andExpect(jsonPath("$.data.workSamples[0].status", is("SCORED")))
                .andReturn().getResponse().getContentAsString();
        for (String body : List.of(mine, detail)) {
            assertFalse(body.contains("NOTE-SECRET"), "scoring notes are staff-only");
            assertFalse(body.contains("ANCHOR-ONE"), "rubric anchors are staff-only");
            assertFalse(body.contains("totalScore"), "scores are staff-only");
            assertFalse(body.contains("\"scores\""), "scores are staff-only");
            assertFalse(body.contains("\"rubric\""), "the rubric is staff-only");
        }
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}", job.getId(), shortlisted.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workSamples[0].scores[0].note", is("NOTE-SECRET")))
                .andExpect(jsonPath("$.data.workSamples[0].responseText", is("My answer")));
        assertTrue(assignments.findById(done.getId()).orElseThrow().getScoredAt() != null);
    }

    // ---- helpers ----

    private Application application(String email, ApplicationStatus status) {
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, email.replace("@", "-") + ".pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(1));
        if (status != ApplicationStatus.SUBMITTED) {
            application.setIdentityRevealedAt(UtcTime.now());
        }
        return screening.withStatus(application, status);
    }

    private WorkSample sample(Job owner, String title, WorkSampleStatus status, Long linkedRequirementId) {
        WorkSample sample = new WorkSample();
        sample.setJob(owner);
        sample.setTitle(title);
        sample.setInstructions("Build an endpoint. SECRET-INSTRUCTIONS");
        sample.setTimeLimitMinutes(30);
        sample.setRubricJson("[{\"requirementId\":" + linkedRequirementId + ",\"criterion\":\"Java design\",\"anchors\":"
                + "{\"1\":\"ANCHOR-ONE\",\"2\":\"weak\",\"3\":\"good\",\"4\":\"excellent\"}},"
                + "{\"requirementId\":null,\"criterion\":\"Communication\",\"anchors\":"
                + "{\"1\":\"unclear\",\"2\":\"partly clear\",\"3\":\"clear\",\"4\":\"very clear\"}}]");
        sample.setStatus(status);
        return workSamples.save(sample);
    }

    private WorkSampleAssignment assignment(WorkSample sample, Application application, WorkSampleAssignmentStatus status,
                                            LocalDateTime startedAt, LocalDateTime dueAt) {
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(sample);
        assignment.setApplication(application);
        assignment.setStatus(status);
        assignment.setAssignedAt(UtcTime.now().minusHours(3));
        assignment.setStartedAt(startedAt);
        assignment.setDueAt(dueAt);
        if (status == WorkSampleAssignmentStatus.SUBMITTED) {
            assignment.setSubmittedAt(UtcTime.now().minusMinutes(1));
            assignment.setResponseText("My answer");
        }
        return assignments.save(assignment);
    }

    private void setStartedAt(Long assignmentId, LocalDateTime startedAt) {
        WorkSampleAssignment assignment = assignments.findById(assignmentId).orElseThrow();
        assignment.setStartedAt(startedAt);
        assignments.save(assignment);
    }

    private RequestBuilder assign(
            SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor principal, Application application,
            WorkSample sample, String dueAt) {
        String body = dueAt == null ? "{\"workSampleId\":" + sample.getId() + "}"
                : "{\"workSampleId\":" + sample.getId() + ",\"dueAt\":\"" + dueAt + "\"}";
        return post(ASSIGN, job.getId(), application.getId()).with(principal)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder score(
            SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor principal, WorkSampleAssignment assignment,
            String scores) {
        return post(SCORE, assignment.getId()).with(principal).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":" + scores + "}");
    }

    private static String score(String criterion, int score, String note) {
        return "{\"requirementId\":null,\"criterion\":\"" + criterion + "\",\"score\":" + score
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private static String criterion(Long requirementId, String name) {
        return "{\"requirementId\":" + requirementId + ",\"criterion\":\"" + name + "\",\"anchors\":"
                + "{\"1\":\"poor\",\"2\":\"basic\",\"3\":\"solid\",\"4\":\"excellent\"}}";
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidate(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }
}
