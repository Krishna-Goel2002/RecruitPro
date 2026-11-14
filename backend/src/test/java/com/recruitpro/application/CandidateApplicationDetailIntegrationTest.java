package com.recruitpro.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.notification.entity.NotificationChannel;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Candidate side (plan 5.5): applying queues matching and notifies, ApplicationDetail shows the status timeline
 * and requirement verification (when the organisation shares it), withdrawal from SHORTLISTED is allowed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateApplicationDetailIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired OrganizationRepository organizations;
    @Autowired BackgroundJobRepository backgroundJobs;
    @Autowired NotificationRepository notifications;

    Organization organization;
    Job job;
    JobRequirement java;
    JobRequirement docker;
    JobRequirement sql;
    Resume resume;
    ResumeAnalysis analysis;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        organization = fixtures.organization("Detail Org");
        HRProfile owner = fixtures.hrMember(organization, "owner@detail.test", Role.HR_MANAGER);
        job = fixtures.job(owner, "Data Engineer", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        docker = screening.requirement(job, RequirementType.SKILL, "Docker", false, false);
        sql = screening.requirement(job, RequirementType.SKILL, "SQL", true, false);
        CandidateProfile candidate = fixtures.candidate("applicant@cand.test");
        resume = screening.resume(candidate, "applicant-cv.pdf");
        analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void applyingQueuesMatchingNotifiesAndTheDetailShowsTimelineAndVerification() throws Exception {
        var me = SecurityMockMvcRequestPostProcessors.user("applicant@cand.test").roles("CANDIDATE");
        String applied = mvc.perform(post("/api/v1/jobs/{jobId}/applications", job.getId()).with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resume.getId() + ",\"coverLetter\":\"  I enjoy data pipelines.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationName", is("Detail Org")))
                .andExpect(jsonPath("$.data.coverLetter", is("I enjoy data pipelines.")))
                .andExpect(jsonPath("$.data.statusChangedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long applicationId = mapper.readTree(applied).path("data").path("id").asLong();
        assertEquals(BackgroundJobStatus.PENDING, backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(
                BackgroundJobType.MATCH_APPLICATION, applicationId).get(0).getStatus());
        assertEquals(1L, emails(NotificationTemplate.APPLICATION_RECEIVED));

        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline.length()", is(1)))
                .andExpect(jsonPath("$.data.timeline[0].status", is("SUBMITTED")))
                .andExpect(jsonPath("$.data.requirements.length()", is(3)))
                .andExpect(jsonPath("$.data.requirements[*].verification", everyItem(is("NOT_VERIFIED"))))
                .andExpect(jsonPath("$.data.workSamples.length()", is(0)))
                .andExpect(jsonPath("$.data.interviews.length()", is(0)));

        Application application = applications.findAllWithCandidateByIdIn(List.of(applicationId)).get(0);
        application.setJob(job);
        screening.currentRun(application, analysis, 50.0, 80.0, true, List.of(
                com.recruitpro.support.ScreeningFixtures.matched(java.getId(), "RESUME_STATED", "RESUME"),
                com.recruitpro.support.ScreeningFixtures.matched(docker.getId(), "SELF_CLAIMED", "CANDIDATE_CLAIM"),
                com.recruitpro.support.ScreeningFixtures.notObserved(sql.getId())), List.of());
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId).with(me))
                .andExpect(jsonPath("$.data.requirements[0].verification", is("VERIFIED")))
                .andExpect(jsonPath("$.data.requirements[1].verification", is("CLAIMED")))
                .andExpect(jsonPath("$.data.requirements[2].verification", is("NOT_VERIFIED")))
                .andExpect(jsonPath("$.data.requirements[0].mandatory", is(true)));

        mvc.perform(post("/api/v1/hr/jobs/{jobId}/applicants/{id}/decisions", job.getId(), applicationId)
                        .with(SecurityMockMvcRequestPostProcessors.user("owner@detail.test").roles("HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHORTLIST\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId).with(me))
                .andExpect(jsonPath("$.data.status", is("SHORTLISTED")))
                .andExpect(jsonPath("$.data.statusChangedAt", notNullValue()))
                .andExpect(jsonPath("$.data.timeline[*].status", contains("SUBMITTED", "SHORTLISTED")));

        organization.setShareVerificationStatus(false);
        organizations.save(organization);
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId).with(me))
                .andExpect(jsonPath("$.data.requirements").doesNotExist());

        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", applicationId).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("WITHDRAWN")));
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", applicationId).with(me))
                .andExpect(jsonPath("$.data.timeline[*].status", contains("SUBMITTED", "SHORTLISTED", "WITHDRAWN")));
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants/{id}/history", job.getId(), applicationId)
                        .with(SecurityMockMvcRequestPostProcessors.user("owner@detail.test").roles("HR_MANAGER")))
                .andExpect(jsonPath("$.data[*].actionType", contains("SHORTLIST", "IDENTITY_REVEALED", "WITHDRAW")))
                .andExpect(jsonPath("$.data[2].actorName", is("Test Candidate")));
        assertEquals(2L, emails(NotificationTemplate.APPLICATION_STATUS_CHANGED));
        mvc.perform(get("/api/v1/candidates/me/applications").with(me))
                .andExpect(jsonPath("$.data.content[0].organizationName", is("Detail Org")))
                .andExpect(jsonPath("$.data.content[0].status", is("WITHDRAWN")));
    }

    /** Email outbox rows of the candidate for a template (registered users also get an IN_APP copy). */
    private long emails(NotificationTemplate template) {
        return notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("applicant@cand.test", template).stream()
                .filter(notification -> notification.getChannel() == NotificationChannel.EMAIL).count();
    }
}
