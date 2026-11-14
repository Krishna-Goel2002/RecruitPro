package com.recruitpro.assessment;

import com.recruitpro.ai.dto.AiInterviewKitRequest;
import com.recruitpro.ai.dto.AiInterviewKitResponse;
import com.recruitpro.ai.dto.AiWorkSampleRequest;
import com.recruitpro.ai.dto.AiWorkSampleResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.interview.repository.InterviewKitRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI drafting of work samples and interview kits (plan 5.6 and 6.1) against a stubbed AI client: the response is
 * mapped and stored (links to requirements of other jobs dropped, missing anchors marked), the request carries the
 * job's requirements v2, an unavailable AI service is 503 and a rejection is 400, with nothing stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthoringGenerationIntegrationTest {

    @MockitoBean AiServiceClient aiServiceClient;

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired WorkSampleRepository workSamples;
    @Autowired InterviewKitRepository kits;

    Job job;
    JobRequirement java;
    JobRequirement docker;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Authoring Org");
        HRProfile manager = fixtures.hrMember(organization, "manager@gen.test", Role.HR_MANAGER);
        fixtures.staff(organization, "recruiter@gen.test", Role.RECRUITER);
        job = fixtures.job(manager, "Accountant", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Tally", true, true);
        docker = screening.requirement(job, RequirementType.EXPERIENCE, "GST compliance", true, false);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void workSampleDraftsAreMappedAndStoredAsDraft() throws Exception {
        Map<String, String> anchors = Map.of("1", "none", "2", "some", "3", "solid", "4", "expert");
        when(aiServiceClient.generateWorkSample(any())).thenReturn(new AiWorkSampleResponse("Accountant work sample",
                "You have 15 minutes. Task 1: reconcile the figures.", 15,
                List.of(new AiWorkSampleResponse.Criterion(java.getId(), "Use of Tally", anchors),
                        new AiWorkSampleResponse.Criterion(987654L, "Foreign link", Map.of("1", "only one")),
                        new AiWorkSampleResponse.Criterion(null, "Clarity", anchors)),
                "rules"));

        mvc.perform(post("/api/v1/hr/jobs/{jobId}/work-samples/generate", job.getId()).with(staff("recruiter@gen.test", "RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/work-samples/generate", job.getId()).with(staff("manager@gen.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.jobId", is(job.getId().intValue())))
                .andExpect(jsonPath("$.data.title", is("Accountant work sample")))
                .andExpect(jsonPath("$.data.timeLimitMinutes", is(15)))
                .andExpect(jsonPath("$.data.rubric.length()", is(3)))
                .andExpect(jsonPath("$.data.rubric[0].requirementId", is(java.getId().intValue())))
                .andExpect(jsonPath("$.data.rubric[1].requirementId", nullValue()))
                .andExpect(jsonPath("$.data.rubric[1].anchors['2']", is("Not described yet")))
                .andExpect(jsonPath("$.data.rubric[2].criterion", is("Clarity")));
        assertEquals(1, workSamples.count());

        ArgumentCaptor<AiWorkSampleRequest> captor = ArgumentCaptor.forClass(AiWorkSampleRequest.class);
        verify(aiServiceClient).generateWorkSample(captor.capture());
        AiWorkSampleRequest sent = captor.getValue();
        assertEquals("Accountant", sent.jobTitle());
        assertEquals(List.of(java.getId(), docker.getId()), sent.requirements().stream().map(r -> r.id()).toList());
        assertEquals("EXPERIENCE", sent.requirements().get(1).type());
    }

    @Test
    void anUnavailableAiServiceIs503AndARejectionIs400() throws Exception {
        var manager = staff("manager@gen.test", "HR_MANAGER");
        doThrow(new ResourceAccessException("Connection refused")).when(aiServiceClient).generateWorkSample(any());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/work-samples/generate", job.getId()).with(manager))
                .andExpect(status().isServiceUnavailable());
        doThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY)).when(aiServiceClient).generateWorkSample(any());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/work-samples/generate", job.getId()).with(manager))
                .andExpect(status().isBadRequest());
        doThrow(new IllegalStateException(AiServiceClient.NOT_CONFIGURED_MESSAGE)).when(aiServiceClient).generateInterviewKit(any());
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/interview-kit/generate", job.getId()).with(manager))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, workSamples.count());
        assertEquals(0, kits.count());
    }

    @Test
    void interviewKitGenerationReplacesTheKit() throws Exception {
        var manager = staff("manager@gen.test", "HR_MANAGER");
        Map<String, String> anchors = Map.of("1", "vague", "2", "partial", "3", "specific", "4", "specific with results");
        when(aiServiceClient.generateInterviewKit(any())).thenReturn(new AiInterviewKitResponse(List.of(
                new AiInterviewKitResponse.Question("q1", docker.getId(), "Tell me about GST work.", List.of("Your part?"), anchors),
                new AiInterviewKitResponse.Question("q1", 987654L, "Duplicate id and foreign link.", null, anchors),
                new AiInterviewKitResponse.Question("q3", null, " ", List.of(), anchors)), "rules"));

        mvc.perform(post("/api/v1/hr/jobs/{jobId}/interview-kit/generate", job.getId()).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generatedBy", is("rules")))
                .andExpect(jsonPath("$.data.questions.length()", is(2)))
                .andExpect(jsonPath("$.data.questions[0].id", is("q1")))
                .andExpect(jsonPath("$.data.questions[0].requirementId", is(docker.getId().intValue())))
                .andExpect(jsonPath("$.data.questions[1].id", is("q2")))
                .andExpect(jsonPath("$.data.questions[1].requirementId", nullValue()))
                .andExpect(jsonPath("$.data.questions[1].followUps.length()", is(0)));
        mvc.perform(post("/api/v1/hr/jobs/{jobId}/interview-kit/generate", job.getId()).with(manager))
                .andExpect(status().isOk());
        assertEquals(1, kits.count(), "one kit per job; regeneration replaces it");

        ArgumentCaptor<AiInterviewKitRequest> captor = ArgumentCaptor.forClass(AiInterviewKitRequest.class);
        verify(aiServiceClient, times(2)).generateInterviewKit(captor.capture());
        assertEquals(2, captor.getValue().requirements().size());
        assertNull(captor.getValue().focusRequirementIds());
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
