package com.recruitpro.hr;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completeness sweep: the everyday lifecycle of drafts. A DRAFT work sample that was never assigned can be deleted
 * (audited); anything else is archived. Work samples can be written by hand and copied. A DRAFT job can be deleted
 * (audited) while published ones are closed, and any job can be copied as a new draft with its requirements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftLifecycleIntegrationTest {

    private static final String SAMPLES = "/api/v1/hr/jobs/{jobId}/work-samples";
    private static final String SAMPLE = "/api/v1/hr/jobs/{jobId}/work-samples/{id}";
    private static final String MANAGER = "manager@drafts-a.test";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired WorkSampleRepository workSamples;
    @Autowired WorkSampleAssignmentRepository assignments;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired AuditEventRepository auditEvents;

    HRProfile manager;
    Job job;
    JobRequirement java;
    WorkSample draft;
    WorkSample active;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization orgA = fixtures.organization("Drafts Org A");
        Organization orgB = fixtures.organization("Drafts Org B");
        manager = fixtures.hrMember(orgA, MANAGER, Role.HR_MANAGER);
        fixtures.staff(orgA, "recruiter@drafts-a.test", Role.RECRUITER);
        fixtures.hrMember(orgB, "manager@drafts-b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        draft = sample(job, "Draft task", WorkSampleStatus.DRAFT, java.getId());
        active = sample(job, "Live task", WorkSampleStatus.ACTIVE, java.getId());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void aDraftWorkSampleCanBeDeletedAndAnythingElseIsArchivedInstead() throws Exception {
        // Recruiters cannot delete; another organisation gets 404.
        mvc.perform(delete(SAMPLE, job.getId(), draft.getId()).with(staff("recruiter@drafts-a.test", "RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(SAMPLE, job.getId(), draft.getId()).with(staff("manager@drafts-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        // An active sample is archived, not deleted.
        mvc.perform(delete(SAMPLE, job.getId(), active.getId()).with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("archive it instead")));
        assertTrue(workSamples.findById(active.getId()).isPresent());

        mvc.perform(delete(SAMPLE, job.getId(), draft.getId()).with(manager())).andExpect(status().isOk());
        assertFalse(workSamples.findById(draft.getId()).isPresent());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.WORK_SAMPLE_DELETED).size());
        mvc.perform(delete(SAMPLE, job.getId(), draft.getId()).with(manager())).andExpect(status().isNotFound());
    }

    @Test
    void anAssignedSampleKeepsItsHistoryAndTheListCountsAssignments() throws Exception {
        Application application = application("cand@drafts.test", ApplicationStatus.SHORTLISTED);
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(active);
        assignment.setApplication(application);
        assignment.setStatus(WorkSampleAssignmentStatus.ASSIGNED);
        assignment.setAssignedAt(UtcTime.now().minusHours(1));
        assignments.save(assignment);

        mvc.perform(get(SAMPLES, job.getId()).with(staff("recruiter@drafts-a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assignmentCount", is(0)))
                .andExpect(jsonPath("$.data[1].assignmentCount", is(1)));
        // Once assigned it can neither go back to draft nor be deleted (even if it somehow were a draft).
        mvc.perform(patch(SAMPLE + "/status", job.getId(), active.getId()).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}")).andExpect(status().isBadRequest());
        active.setStatus(WorkSampleStatus.DRAFT);
        workSamples.save(active);
        mvc.perform(delete(SAMPLE, job.getId(), active.getId()).with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("has been assigned")));
        assertTrue(workSamples.findById(active.getId()).isPresent());
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(AuditActions.WORK_SAMPLE_DELETED).isEmpty());
    }

    @Test
    void workSamplesCanBeWrittenByHandAndCopied() throws Exception {
        String body = "{\"title\":\"Hand-written task\",\"instructions\":\"Design a rate limiter\",\"timeLimitMinutes\":45,"
                + "\"rubric\":[" + criterion(java.getId(), "Java design") + "]}";
        mvc.perform(post(SAMPLES, job.getId()).with(manager()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.title", is("Hand-written task")))
                .andExpect(jsonPath("$.data.assignmentCount", is(0)));
        mvc.perform(post(SAMPLES, job.getId()).with(staff("recruiter@drafts-a.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String incomplete = "{\"title\":\"x\",\"instructions\":\"y\",\"timeLimitMinutes\":45,\"rubric\":[]}";
        mvc.perform(post(SAMPLES, job.getId()).with(manager()).contentType(MediaType.APPLICATION_JSON).content(incomplete))
                .andExpect(status().isBadRequest());

        mvc.perform(post(SAMPLE + "/duplicate", job.getId(), active.getId()).with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.title", is("Live task (copy)")))
                .andExpect(jsonPath("$.data.rubric[0].requirementId", is(java.getId().intValue())))
                .andExpect(jsonPath("$.data.timeLimitMinutes", is(30)));
        mvc.perform(post(SAMPLE + "/duplicate", job.getId(), active.getId()).with(staff("manager@drafts-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        assertEquals(4, workSamples.findAllByJobIdOrderByIdAsc(job.getId()).size());
    }

    @Test
    void onlyDraftJobsCanBeDeletedAndAnyJobCanBeCopied() throws Exception {
        Job draftJob = fixtures.job(manager, "Data Engineer", JobStatus.DRAFT);
        screening.requirement(draftJob, RequirementType.SKILL, "SQL", true, true);
        sample(draftJob, "Draft job task", WorkSampleStatus.DRAFT, null);

        mvc.perform(delete("/api/v1/jobs/{id}", job.getId()).with(manager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("close the job instead")));
        mvc.perform(delete("/api/v1/jobs/{id}", draftJob.getId()).with(staff("manager@drafts-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/jobs/{id}", draftJob.getId()).with(manager())).andExpect(status().isOk());
        assertFalse(jobs.findById(draftJob.getId()).isPresent());
        assertTrue(workSamples.findAllByJobIdOrderByIdAsc(draftJob.getId()).isEmpty());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.JOB_DELETED).size());

        screening.requirement(job, RequirementType.SKILL, "Docker", false, false);
        mvc.perform(post("/api/v1/jobs/{id}/duplicate", job.getId()).with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.title", is("Backend Engineer (copy)")))
                .andExpect(jsonPath("$.data.applicationCount", is(0)))
                .andExpect(jsonPath("$.data.requirements.length()", is(2)))
                .andExpect(jsonPath("$.data.requirements[0].description", is("Java")));
        mvc.perform(post("/api/v1/jobs/{id}/duplicate", job.getId()).with(staff("recruiter@drafts-a.test", "RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs/{id}/duplicate", job.getId()).with(staff("manager@drafts-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        List<Job> all = jobs.findAll();
        assertEquals(2, all.size());
        Job copy = all.stream().filter(j -> j.getStatus() == JobStatus.DRAFT).findFirst().orElseThrow();
        assertEquals(job.getOrganizationId(), copy.getOrganizationId());
        assertEquals(2, requirements.findByJobIdOrderByDisplayOrderAscIdAsc(copy.getId()).size());
        // The source keeps its own requirements.
        assertEquals(2, requirements.findByJobIdOrderByDisplayOrderAscIdAsc(job.getId()).size());
    }

    // ---- helpers ----

    private Application application(String email, ApplicationStatus status) {
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, email.replace("@", "-") + ".pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(1));
        application.setIdentityRevealedAt(UtcTime.now());
        return screening.withStatus(application, status);
    }

    private WorkSample sample(Job owner, String title, WorkSampleStatus status, Long linkedRequirementId) {
        WorkSample sample = new WorkSample();
        sample.setJob(owner);
        sample.setTitle(title);
        sample.setInstructions("Build an endpoint.");
        sample.setTimeLimitMinutes(30);
        sample.setRubricJson("[" + criterion(linkedRequirementId, "Java design") + "]");
        sample.setStatus(status);
        return workSamples.save(sample);
    }

    private static String criterion(Long requirementId, String name) {
        return "{\"requirementId\":" + requirementId + ",\"criterion\":\"" + name + "\",\"anchors\":"
                + "{\"1\":\"poor\",\"2\":\"basic\",\"3\":\"solid\",\"4\":\"excellent\"}}";
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor manager() {
        return staff(MANAGER, "HR_MANAGER");
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
