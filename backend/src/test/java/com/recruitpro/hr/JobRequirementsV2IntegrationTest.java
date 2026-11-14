package com.recruitpro.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.JobDeadlineCloser;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
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

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Requirements v2 and job editing (plan 3.4, 5.1, 5.3): defaults, bulk replace, versioning and re-matching after
 * publication, JobPublic for candidates (M3, L1), the deadline closer and the compile proxy.
 */
@SpringBootTest(properties = {
        // An unreachable AI service: the compile proxy must answer 503, never 500 (and never reach a local service).
        "app.ai-service.base-url=http://127.0.0.1:1",
        "app.ai-service.service-token=test-only-token"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobRequirementsV2IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired BackgroundJobRepository backgroundJobs;
    @Autowired AuditEventRepository auditEvents;
    @Autowired JobDeadlineCloser deadlineCloser;

    Organization organization;
    HRProfile owner;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        organization = fixtures.organization("Requirements Org");
        owner = fixtures.hrMember(organization, "owner@req.test", Role.HR_MANAGER);
        fixtures.staff(organization, "recruiter@req.test", Role.RECRUITER);
        fixtures.hrMember(fixtures.organization("Other Requirements Org"), "other@req.test", Role.HR_MANAGER);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void requirementDefaultsValidationAndBulkReplaceOnADraft() throws Exception {
        var manager = user("owner@req.test", "HR_MANAGER");
        String created = mvc.perform(post("/api/v1/jobs").with(manager).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirementsVersion", is(1)))
                .andExpect(jsonPath("$.data.createdByName", is("Test User")))
                .andExpect(jsonPath("$.data.applicationCount", is(0)))
                .andReturn().getResponse().getContentAsString();
        long jobId = mapper.readTree(created).path("data").path("id").asLong();

        String java = mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\" Java \",\"alternatives\":[\"Kotlin\",\" \",\"kotlin\",\"Java\"],\"proficiency\":\"ADVANCED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description", is("Java")))
                .andExpect(jsonPath("$.data.mandatory", is(true)))
                .andExpect(jsonPath("$.data.knockout", is(true)))
                .andExpect(jsonPath("$.data.alternatives", contains("Kotlin")))
                .andExpect(jsonPath("$.data.proficiency", is("ADVANCED")))
                .andReturn().getResponse().getContentAsString();
        long javaId = mapper.readTree(java).path("data").path("id").asLong();
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Docker\",\"mandatory\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knockout", is(false)))
                .andExpect(jsonPath("$.data.alternatives.length()", is(0)));
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Go\",\"mandatory\":false,\"knockout\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPERIENCE\",\"description\":\"Backend\",\"minimumYears\":51}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Go\",\"proficiency\":\"GURU\"}"))
                .andExpect(status().isBadRequest());

        String replaced = mvc.perform(put("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":[{\"id\":" + javaId + ",\"type\":\"SKILL\",\"description\":\"Java 21\"},"
                                + "{\"type\":\"EXPERIENCE\",\"description\":\"Backend development\",\"minimumYears\":3,\"knockout\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirements.length()", is(2)))
                .andExpect(jsonPath("$.data.requirements[0].id", is((int) javaId)))
                .andExpect(jsonPath("$.data.requirements[0].description", is("Java 21")))
                .andExpect(jsonPath("$.data.requirements[0].alternatives.length()", is(0)))
                .andExpect(jsonPath("$.data.requirements[1].type", is("EXPERIENCE")))
                .andExpect(jsonPath("$.data.requirements[1].mandatory", is(true)))
                .andExpect(jsonPath("$.data.requirements[1].knockout", is(false)))
                .andExpect(jsonPath("$.data.requirementsVersion", is(1)))
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, mapper.readTree(replaced).path("data").path("requirements").size());
        assertEquals(2, requirements.findByJobIdOrderById(jobId).size());
        assertTrue(auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(),
                AuditActions.JOB_REQUIREMENTS_CHANGED).isEmpty(), "draft edits are not versioned");

        Job other = fixtures.job(owner, "Another draft", JobStatus.DRAFT);
        JobRequirement foreign = screening.requirement(other, RequirementType.SKILL, "SQL", true, true);
        mvc.perform(put("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":[{\"id\":" + foreign.getId() + ",\"type\":\"SKILL\",\"description\":\"SQL\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/jobs/{id}/requirements", jobId).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":[{\"type\":\"SKILL\",\"description\":\"\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/jobs/{id}/requirements", jobId).with(user("recruiter@req.test", "RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requirements\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/jobs/{id}/requirements", jobId).with(user("other@req.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requirements\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editingRequirementsOfAPublishedJobVersionsAuditsAndRequeuesMatching() throws Exception {
        Job job = fixtures.job(owner, "Published role", JobStatus.PUBLISHED);
        JobRequirement java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        Application open = application(job, "open@cand.test");
        Application withdrawn = screening.withStatus(application(job, "gone@cand.test"), ApplicationStatus.WITHDRAWN);
        var manager = user("owner@req.test", "HR_MANAGER");

        mvc.perform(put("/api/v1/jobs/{id}/requirements/{rid}", job.getId(), java.getId()).with(manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"SKILL\",\"description\":\"Java 21\"}"))
                .andExpect(status().isOk());
        assertEquals(2, jobs.findById(job.getId()).orElseThrow().getRequirementsVersion());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(),
                AuditActions.JOB_REQUIREMENTS_CHANGED).size());
        assertEquals(BackgroundJobStatus.PENDING, backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(
                BackgroundJobType.MATCH_APPLICATION, open.getId()).get(0).getStatus());
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, withdrawn.getId()).isEmpty());

        mvc.perform(post("/api/v1/jobs/{id}/requirements", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Docker\",\"mandatory\":false}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/jobs/{id}/requirements/{rid}", job.getId(), java.getId()).with(manager))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/jobs/{id}", job.getId()).with(manager))
                .andExpect(jsonPath("$.data.requirementsVersion", is(4)))
                .andExpect(jsonPath("$.data.applicationCount", is(2)));
        assertEquals(1, backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, open.getId()).size(),
                "a queued re-match is not duplicated");

        mvc.perform(put("/api/v1/jobs/{id}", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson().replace("Platform Engineer", "Platform Engineer II")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title", is("Platform Engineer II")));
        mvc.perform(patch("/api/v1/jobs/{id}/status", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ON_HOLD\"}"))
                .andExpect(status().isOk());
        String deadline = UtcTime.format(UtcTime.now().plusDays(45));
        mvc.perform(put("/api/v1/jobs/{id}", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson().replace("\"vacancies\":2", "\"vacancies\":2,\"deadline\":\"" + deadline + "\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deadline", notNullValue()));
        mvc.perform(put("/api/v1/jobs/{id}", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson().replace("\"vacancies\":2", "\"vacancies\":2,\"deadline\":\"2020-01-01T00:00:00Z\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/jobs/{id}/status", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedReason", is("CLOSED_BY_HR")));
        mvc.perform(put("/api/v1/jobs/{id}", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs/{id}/requirements", job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Go\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void candidatesGetJobPublicForOpenJobsOnlyAndStaffGetJobStaff() throws Exception {
        Job open = fixtures.job(owner, "Open role", JobStatus.PUBLISHED);
        screening.requirement(open, RequirementType.SKILL, "Java", true, true);
        Job expired = fixtures.job(owner, "Expired role", JobStatus.PUBLISHED);
        expired.setDeadline(UtcTime.now().minusHours(1));
        jobs.save(expired);
        fixtures.job(owner, "Draft role", JobStatus.DRAFT);
        application(open, "applicant@cand.test");
        fixtures.candidate("browser@cand.test");

        String body = mvc.perform(get("/api/v1/jobs").with(user("browser@cand.test", "CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].title", is("Open role")))
                .andExpect(jsonPath("$.data.content[0].organizationName", is("Requirements Org")))
                .andExpect(jsonPath("$.data.content[0].requirements[0].knockout", is(true)))
                .andExpect(jsonPath("$.data.content[0].createdByEmail").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].applicationCount").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("owner@req.test"), "the creator's email is never shown to candidates");
        mvc.perform(get("/api/v1/jobs/{id}", expired.getId()).with(user("browser@cand.test", "CANDIDATE")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/{id}", open.getId()).with(user("browser@cand.test", "CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationName", is("Requirements Org")))
                .andExpect(jsonPath("$.data.createdByEmail").doesNotExist());

        JsonNode staff = mapper.readTree(mvc.perform(get("/api/v1/jobs").param("sort", "title").param("direction", "ASC")
                        .with(user("recruiter@req.test", "RECRUITER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
        assertEquals(3, staff.size());
        assertEquals("Expired role", staff.get(1).path("title").asText());
        JsonNode openStaff = staff.get(2);
        assertEquals("Open role", openStaff.path("title").asText());
        assertEquals("owner@req.test", openStaff.path("createdByEmail").asText());
        assertEquals("Test User", openStaff.path("createdByName").asText());
        assertEquals(1, openStaff.path("applicationCount").asInt());
        assertEquals(1, openStaff.path("requirementsVersion").asInt());
        assertEquals(1, openStaff.path("requirements").size());
    }

    @Test
    void theDeadlineCloserClosesExpiredPublishedJobsOnce() {
        Job expired = fixtures.job(owner, "Expired role", JobStatus.PUBLISHED);
        expired.setDeadline(UtcTime.now().minusMinutes(5));
        expired = jobs.save(expired);
        Job onHold = fixtures.job(owner, "On hold role", JobStatus.ON_HOLD);
        onHold.setDeadline(UtcTime.now().minusMinutes(5));
        onHold = jobs.save(onHold);
        Job future = fixtures.job(owner, "Future role", JobStatus.PUBLISHED);
        future.setDeadline(UtcTime.now().plusDays(3));
        future = jobs.save(future);

        assertEquals(1, deadlineCloser.closeExpiredJobs());
        Job closed = jobs.findById(expired.getId()).orElseThrow();
        assertEquals(JobStatus.CLOSED, closed.getStatus());
        assertEquals(JobDeadlineCloser.DEADLINE_PASSED, closed.getClosedReason());
        assertEquals(JobStatus.ON_HOLD, jobs.findById(onHold.getId()).orElseThrow().getStatus());
        assertEquals(JobStatus.PUBLISHED, jobs.findById(future.getId()).orElseThrow().getStatus());
        assertEquals(1, auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(), AuditActions.JOB_AUTO_CLOSED).size());
        assertEquals(0, deadlineCloser.closeExpiredJobs());
    }

    @Test
    void compilingRequirementsIsForManagersAndReportsAnUnavailableService() throws Exception {
        String body = "{\"title\":\"Accountant\",\"description\":\"Requirements: 3+ years of GST and Tally\",\"experienceLevel\":\"MID\"}";
        mvc.perform(post("/api/v1/jobs/compile-requirements").with(user("recruiter@req.test", "RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs/compile-requirements").with(user("owner@req.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs/compile-requirements").with(user("owner@req.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success", is(false)));
        assertEquals(0, requirements.count());
    }

    private Application application(Job job, String email) {
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, "resume.pdf");
        return screening.application(candidate, job, resume, UtcTime.now());
    }

    private static String jobJson() {
        return """
                {"title":"Platform Engineer","description":"Build the platform","department":"Engineering",
                "location":"Remote","jobType":"FULL_TIME","experienceLevel":"MID","minimumSalary":50000,
                "maximumSalary":90000,"vacancies":2}
                """;
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor user(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

}
