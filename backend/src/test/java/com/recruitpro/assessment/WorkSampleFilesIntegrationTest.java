package com.recruitpro.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.assessment.repository.WorkSampleSubmissionFileRepository;
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
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.AuthTestSupport;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-PLAT work-sample additions: file attachments with a submission (validation, storage, candidate and audited staff
 * downloads, tenancy) and HR cancellation of an assignment with a reason (notification, audit, no longer evidence).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkSampleFilesIntegrationTest {

    private static final String MINE = "/api/v1/candidates/me/work-samples";
    private static final String STAFF_FILE = "/api/v1/hr/work-sample-assignments/{id}/files/{fileId}";
    private static final String CANCEL = "/api/v1/hr/work-sample-assignments/{id}/cancel";
    private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired WorkSampleRepository workSamples;
    @Autowired WorkSampleAssignmentRepository assignments;
    @Autowired WorkSampleSubmissionFileRepository files;
    @Autowired AuditEventRepository auditEvents;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobService backgroundJobService;

    Job job;
    JobRequirement javaRequirement;
    Application applicationA;
    Application applicationB;
    WorkSample sample;
    WorkSample sample2;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization orgA = fixtures.organization("Files Org A");
        Organization orgB = fixtures.organization("Files Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@wsf-a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "recruiter@wsf-a.test", Role.RECRUITER);
        fixtures.hrMember(orgB, "manager@wsf-b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
        javaRequirement = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        applicationA = application("a@wsf-cand.test");
        applicationB = application("b@wsf-cand.test");
        sample = sample("Task one");
        sample2 = sample("Task two");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void candidatesSubmitFilesThatStaffAndTheCandidateCanDownload() throws Exception {
        WorkSampleAssignment started = assignment(sample, applicationA, WorkSampleAssignmentStatus.STARTED);
        MvcResult result = mvc.perform(multipart(MINE + "/{id}/submit", started.getId())
                        .file(new MockMultipartFile("files", "answer.pdf", "application/pdf", PDF))
                        .file(new MockMultipartFile("files", "C:\\fakepath\\diagram.png", "image/png", PNG))
                        .param("responseText", "See the attached files")
                        .param("responseUrl", "https://git.example.test/repo")
                        .with(candidate("a@wsf-cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.files.length()", is(2)))
                .andExpect(jsonPath("$.data.files[0].originalName", is("answer.pdf")))
                .andExpect(jsonPath("$.data.files[0].contentType", is("application/pdf")))
                .andExpect(jsonPath("$.data.files[0].sizeBytes", is(PDF.length)))
                .andExpect(jsonPath("$.data.files[1].originalName", is("diagram.png")))
                .andReturn();
        JsonNode data = AuthTestSupport.data(mapper, result);
        long fileId = data.path("files").get(0).path("id").asLong();
        assertEquals(64, data.path("files").get(0).path("sha256").asText().length());

        mvc.perform(get("/api/v1/hr/jobs/{jobId}/work-sample-assignments", job.getId()).with(staff("recruiter@wsf-a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].files.length()", is(2)))
                .andExpect(jsonPath("$.data[0].files[1].contentType", is("image/png")));

        MvcResult download = mvc.perform(get(STAFF_FILE, started.getId(), fileId).with(staff("recruiter@wsf-a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("answer.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertArrayEquals(PDF, download.getResponse().getContentAsByteArray());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.WORK_SAMPLE_FILE_DOWNLOADED).size());

        mvc.perform(get(STAFF_FILE, started.getId(), fileId).with(staff("manager@wsf-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(get(STAFF_FILE, started.getId(), fileId).with(staff("interviewer@wsf-a.test", "INTERVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(STAFF_FILE, started.getId(), fileId + 999).with(staff("recruiter@wsf-a.test", "RECRUITER")))
                .andExpect(status().isNotFound());

        mvc.perform(get(MINE + "/{id}/files/{fileId}", started.getId(), fileId).with(candidate("a@wsf-cand.test")))
                .andExpect(status().isOk());
        mvc.perform(get(MINE + "/{id}/files/{fileId}", started.getId(), fileId).with(candidate("b@wsf-cand.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadsAreValidatedBeforeAnythingChanges() throws Exception {
        WorkSampleAssignment started = assignment(sample, applicationA, WorkSampleAssignmentStatus.STARTED);
        mvc.perform(multipart(MINE + "/{id}/submit", started.getId())
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", PDF))
                        .file(new MockMultipartFile("files", "b.pdf", "application/pdf", PDF))
                        .file(new MockMultipartFile("files", "c.pdf", "application/pdf", PDF))
                        .file(new MockMultipartFile("files", "d.pdf", "application/pdf", PDF))
                        .param("responseText", "Four files").with(candidate("a@wsf-cand.test")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(MINE + "/{id}/submit", started.getId())
                        .file(new MockMultipartFile("files", "fake.pdf", "application/pdf", PNG))
                        .param("responseText", "Disguised").with(candidate("a@wsf-cand.test")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(MINE + "/{id}/submit", started.getId())
                        .file(new MockMultipartFile("files", "tool.exe", "application/octet-stream", PDF))
                        .with(candidate("a@wsf-cand.test")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(MINE + "/{id}/submit", started.getId()).with(candidate("a@wsf-cand.test")))
                .andExpect(status().isBadRequest());
        assertEquals(WorkSampleAssignmentStatus.STARTED, assignments.findById(started.getId()).orElseThrow().getStatus());
        assertTrue(files.findAllByAssignmentIdOrderByIdAsc(started.getId()).isEmpty());

        // Files alone are a valid submission; JSON submissions keep working.
        mvc.perform(multipart(MINE + "/{id}/submit", started.getId())
                        .file(new MockMultipartFile("files", "only.pdf", "application/pdf", PDF))
                        .with(candidate("a@wsf-cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()", is(1)));
        WorkSampleAssignment second = assignment(sample2, applicationA, WorkSampleAssignmentStatus.STARTED);
        mvc.perform(post(MINE + "/{id}/submit", second.getId()).with(candidate("a@wsf-cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"responseText\":\"Text only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()", is(0)));
    }

    @Test
    void hrCancelsAnAssignmentWithAReason() throws Exception {
        WorkSampleAssignment scored = assignment(sample, applicationA, WorkSampleAssignmentStatus.SCORED);
        WorkSampleAssignment started = assignment(sample, applicationB, WorkSampleAssignmentStatus.STARTED);
        String body = "{\"reason\":\"The role was re-scoped; this task no longer applies.\"}";

        mvc.perform(post(CANCEL, scored.getId()).with(staff("recruiter@wsf-a.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(CANCEL, scored.getId()).with(staff("manager@wsf-b.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(CANCEL, scored.getId()).with(staff("manager@wsf-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(CANCEL, scored.getId()).with(staff("manager@wsf-a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANCELLED")))
                .andExpect(jsonPath("$.data.cancelReason", is("The role was re-scoped; this task no longer applies.")))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.cancelledByName", is("Test User")));
        mvc.perform(post(CANCEL, scored.getId()).with(staff("manager@wsf-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());

        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.WORK_SAMPLE_CANCELLED).size());
        assertEquals(1, notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("a@wsf-cand.test",
                NotificationTemplate.WORK_SAMPLE_CANCELLED).stream().filter(n -> n.getChannel().name().equals("EMAIL")).count());
        assertFalse(backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, applicationA.getId()).isEmpty(),
                "a cancelled scored sample stops counting as evidence, so the application is re-matched");
        assertTrue(assignments.findAllForApplicationAndStatus(applicationA.getId(), WorkSampleAssignmentStatus.SCORED).isEmpty());

        mvc.perform(get(MINE).with(candidate("a@wsf-cand.test")))
                .andExpect(jsonPath("$.data[0].status", is("CANCELLED")))
                .andExpect(jsonPath("$.data[0].cancelReason", is("The role was re-scoped; this task no longer applies.")));

        mvc.perform(post(CANCEL, started.getId()).with(staff("manager@wsf-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post(MINE + "/{id}/submit", started.getId()).with(candidate("b@wsf-cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"responseText\":\"Too late\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    private Application application(String email) {
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, email.replace("@", "-") + ".pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(1));
        application.setIdentityRevealedAt(UtcTime.now());
        return screening.withStatus(application, ApplicationStatus.SHORTLISTED);
    }

    private WorkSample sample(String title) {
        WorkSample created = new WorkSample();
        created.setJob(job);
        created.setTitle(title);
        created.setInstructions("Build an endpoint.");
        created.setTimeLimitMinutes(30);
        created.setRubricJson("[{\"requirementId\":" + javaRequirement.getId() + ",\"criterion\":\"Java design\",\"anchors\":"
                + "{\"1\":\"poor\",\"2\":\"weak\",\"3\":\"good\",\"4\":\"excellent\"}}]");
        created.setStatus(WorkSampleStatus.ACTIVE);
        return workSamples.save(created);
    }

    private WorkSampleAssignment assignment(WorkSample workSample, Application application, WorkSampleAssignmentStatus status) {
        LocalDateTime now = UtcTime.now();
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(workSample);
        assignment.setApplication(application);
        assignment.setStatus(status);
        assignment.setAssignedAt(now.minusHours(1));
        assignment.setStartedAt(now.minusMinutes(5));
        if (status == WorkSampleAssignmentStatus.SCORED) {
            assignment.setSubmittedAt(now.minusMinutes(2));
            assignment.setResponseText("Answer");
            assignment.setScoresJson("[{\"requirementId\":" + javaRequirement.getId() + ",\"criterion\":\"Java design\",\"score\":4,\"note\":null}]");
            assignment.setTotalScore(new java.math.BigDecimal("4.00"));
            assignment.setScoredAt(now.minusMinutes(1));
        }
        return assignments.save(assignment);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidate(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }
}
