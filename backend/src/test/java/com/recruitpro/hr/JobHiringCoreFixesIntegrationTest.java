package com.recruitpro.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.audit.entity.AuditEvent;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.JobDeadlineCloser;
import com.recruitpro.hr.service.JobService;
import com.recruitpro.organization.entity.Organization;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R2-H01 (OTHER requirements are never knockouts), R2-M03 (HR job status changes are audited), R2-M04 (HR edits and
 * transitions lock the job row, so a concurrent close is never overwritten) and R2-L01 (salary precision).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobHiringCoreFixesIntegrationTest {

    private static final String MANAGER = "manager@jobfix.test";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PlatformTransactionManager transactionManager;

    Organization organization;
    Job published;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        organization = fixtures.organization("Job Fix Org");
        HRProfile manager = fixtures.hrMember(organization, MANAGER, Role.HR_MANAGER);
        published = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void otherRequirementsAreNeverKnockouts() throws Exception {
        mvc.perform(post("/api/v1/jobs/{id}/requirements", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OTHER\",\"description\":\"Comfortable with on-call rotations\",\"mandatory\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mandatory", is(true)))
                .andExpect(jsonPath("$.data.knockout", is(false)));
        mvc.perform(put("/api/v1/jobs/{id}/requirements", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirements\":[{\"type\":\"OTHER\",\"description\":\"Night shifts\",\"knockout\":true},"
                                + "{\"type\":\"SKILL\",\"description\":\"Java\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirements[0].knockout", is(false)))
                .andExpect(jsonPath("$.data.requirements[1].knockout", is(true)));
    }

    @Test
    void hrStatusChangesAreAudited() throws Exception {
        mvc.perform(patch("/api/v1/jobs/{id}/status", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_HOLD\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/jobs/{id}/status", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedReason", is(JobService.CLOSED_BY_HR)));
        mvc.perform(patch("/api/v1/jobs/{id}/status", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest());

        List<AuditEvent> events = auditEvents.findAllByOrganizationIdAndActionOrderByIdAsc(organization.getId(),
                JobService.AUDIT_JOB_STATUS_CHANGED);
        assertEquals(2, events.size(), "refused transitions are not audited");
        assertEquals(String.valueOf(published.getId()), events.get(0).getTargetId());
        assertTrue(events.get(0).getDetails().contains("PUBLISHED -> ON_HOLD"));
        assertTrue(events.get(1).getDetails().contains("ON_HOLD -> CLOSED"));
        assertEquals(MANAGER, events.get(1).getActorEmail());
    }

    @Test
    void anEditRacingAConcurrentCloseNeverReopensTheJob() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> closer = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            Job job = jobs.lockById(published.getId()).orElseThrow();
            job.transitionTo(JobStatus.CLOSED);
            job.setClosedReason(JobDeadlineCloser.DEADLINE_PASSED);
            jobs.saveAndFlush(job);
            locked.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        MvcResult edit = mvc.perform(put("/api/v1/jobs/{id}", published.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(jobJson("Backend Engineer (edited)", "50000")))
                .andReturn();
        closer.get(10, TimeUnit.SECONDS);

        assertEquals(400, edit.getResponse().getStatus(), "the edit sees the closed job once it gets the row lock");
        Job stored = jobs.findById(published.getId()).orElseThrow();
        assertEquals(JobStatus.CLOSED, stored.getStatus());
        assertEquals("Backend Engineer", stored.getTitle());
    }

    @Test
    void salariesBeyondTheColumnPrecisionAre400NotAServerError() throws Exception {
        mvc.perform(post("/api/v1/jobs").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson("Data Engineer", "12345678901.00")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson("Data Engineer", "50000.123")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson("Data Engineer", "50000.50")))
                .andExpect(status().isOk());
    }

    private static String jobJson(String title, String minimumSalary) {
        return "{\"title\":\"" + title + "\",\"description\":\"Build services\",\"department\":\"Engineering\","
                + "\"location\":\"Remote\",\"jobType\":\"FULL_TIME\",\"experienceLevel\":\"MID\",\"minimumSalary\":"
                + minimumSalary + ",\"vacancies\":1}";
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor manager() {
        return SecurityMockMvcRequestPostProcessors.user(MANAGER).roles("HR_MANAGER");
    }
}
