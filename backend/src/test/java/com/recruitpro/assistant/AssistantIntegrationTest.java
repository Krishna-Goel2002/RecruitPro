package com.recruitpro.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiAssistantStepRequest;
import com.recruitpro.ai.dto.AiAssistantStepResponse;
import com.recruitpro.ai.dto.AiCompileResponse;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
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
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.retention.RetentionPurgeService;
import com.recruitpro.retention.RetentionReport;
import com.recruitpro.security.ratelimit.RateLimiterStore;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HR assistant orchestrator (AS-BE, ASSISTANT_PLAN 3.2): conversations and ownership, tool catalogue per role, the
 * tool loop with de-identified observations, proposed actions (create_job draft/publish, decisions, expiry, cancel,
 * double confirm), the rate limit, the unavailable planner, the organisation's external-model switch and retention.
 * The ai-service planner is mocked ({@code AiServiceClient.assistantStep}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantIntegrationTest {

    private static final String BASE = "/api/v1/assistant";
    private static final LocalDateTime T0 = LocalDateTime.parse("2026-10-01T09:00:00");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired JobRepository jobs;
    @Autowired JobRequirementRepository requirements;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;
    @Autowired RateLimiterStore rateLimiterStore;
    @Autowired RetentionPurgeService retentionPurgeService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean AiServiceClient aiServiceClient;

    Organization orgA;
    Organization orgB;
    HRProfile manager;
    Job job;
    JobRequirement java;
    Application hidden;
    Application revealed;
    Application otherOrg;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        rateLimiterStore.reset();
        orgA = fixtures.organization("Assistant Org A");
        orgB = fixtures.organization("Assistant Org B");
        manager = fixtures.hrMember(orgA, "manager@a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "manager2@a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "admin@a.test", Role.ADMIN);
        fixtures.staff(orgA, "recruiter@a.test", Role.RECRUITER);
        fixtures.staff(orgA, "interviewer@a.test", Role.INTERVIEWER);
        HRProfile managerB = fixtures.hrMember(orgB, "manager@b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Backend Engineer", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);

        hidden = applicant(job, "priya@cand.test", "Priya", "Sharmaville", 0,
                "Priya Sharmaville (priya@cand.test, +91 98765 43210, https://github.com/priyash) built Java services");
        revealed = applicant(job, "rahul@cand.test", "Rahul", "Verstappen", 5, "Built Java services at scale");
        revealed.setStatus(ApplicationStatus.SHORTLISTED);
        revealed.setIdentityRevealedAt(UtcTime.now());
        revealed = applications.saveAndFlush(revealed);
        Job jobB = fixtures.job(managerB, "Other Org Job", JobStatus.PUBLISHED);
        otherOrg = applicant(jobB, "zed@cand.test", "Zed", "Outsider", 1, "Other evidence");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    void conversationsBelongToTheirOwner() throws Exception {
        long id = createConversation("manager@a.test", "HR_MANAGER");
        mvc.perform(get(BASE + "/conversations").with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.content[0].id", is((int) id)))
                .andExpect(jsonPath("$.data.content[0].title", is("New conversation")));
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()", is(0)));

        // Another user of the same organisation sees nothing and cannot act on it.
        mvc.perform(get(BASE + "/conversations").with(staff("manager2@a.test", "HR_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements", is(0)));
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager2@a.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/conversations/{id}/messages", id).with(staff("manager2@a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/conversations/{id}", id).with(staff("manager2@a.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        // Candidates have no assistant.
        mvc.perform(post(BASE + "/conversations").with(staff("someone@cand.test", "CANDIDATE")))
                .andExpect(status().isForbidden());

        when(aiServiceClient.assistantStep(any())).thenReturn(answer("Hello", List.of()));
        send(id, "manager@a.test", "HR_MANAGER", "Hello there", null);
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title", is("Hello there")))
                .andExpect(jsonPath("$.data.messages.length()", is(2)))
                .andExpect(jsonPath("$.data.messages[0].role", is("user")))
                .andExpect(jsonPath("$.data.messages[1].role", is("assistant")))
                .andExpect(jsonPath("$.data.messages[1].planner", is("offline")));

        mvc.perform(delete(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        assertEquals(0, count("SELECT COUNT(*) FROM assistant_messages"));
    }

    @Test
    void theToolCatalogueFollowsTheRole() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(answer("ok", List.of()));
        assertEquals(List.of("list_jobs", "job_insights", "applicant_insights", "compare_applicants", "attention_digest",
                        "my_interviews", "fairness_summary", "explain", "create_job", "update_job_status",
                        "decide_application", "assign_work_sample", "schedule_interview", "generate_work_sample",
                        "generate_interview_kit"),
                catalogue("manager@a.test", "HR_MANAGER"));
        assertEquals(List.of("list_jobs", "job_insights", "applicant_insights", "compare_applicants", "attention_digest",
                "my_interviews", "explain"), catalogue("recruiter@a.test", "RECRUITER"));
        assertEquals(List.of("applicant_insights", "my_interviews", "explain"), catalogue("interviewer@a.test", "INTERVIEWER"));

        AiAssistantStepRequest request = lastRequest();
        Map<String, Object> explain = request.tools().get(2);
        assertEquals(false, explain.get("writes"));
        assertTrue(String.valueOf(((Map<?, ?>) explain.get("args")).get("topic")).startsWith("string, required, one of"));
        assertEquals("INTERVIEWER", request.context().get("role"));
    }

    @Test
    void anInterviewerCannotUseToolsOutsideTheirCatalogueOrSeeApplicantsTheyDoNotInterview() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(
                toolCall("decide_application", Map.of("applicationCode", code(hidden), "decision", "REJECT", "reason", "x")),
                toolCall("applicant_insights", Map.of("applicationCode", code(hidden))),
                answer("Done", List.of()));
        long id = createConversation("interviewer@a.test", "INTERVIEWER");
        send(id, "interviewer@a.test", "INTERVIEWER", "Reject " + code(hidden), null);
        List<AiAssistantStepRequest> requests = requests(3);
        Map<String, Object> first = requests.get(1).observations().get(0);
        assertEquals(false, first.get("ok"));
        assertTrue(String.valueOf(first.get("error")).contains("Unknown tool"));
        Map<String, Object> second = requests.get(2).observations().get(1);
        assertEquals(false, second.get("ok"));
        assertTrue(String.valueOf(second.get("error")).contains("interview"), String.valueOf(second.get("error")));
        assertEquals(0, count("SELECT COUNT(*) FROM assistant_actions"));
    }

    @Test
    void toolCallsRunAsTheUserAndThePlannerNeverSeesNamesOrContactDetails() throws Exception {
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("kind", "candidates");
        candidates.put("items", List.of(
                Map.of("code", code(revealed), "verified", 80.0, "group", "RANKED"),
                Map.of("code", code(hidden)),
                Map.of("code", code(otherOrg))));
        when(aiServiceClient.assistantStep(any())).thenReturn(
                toolCall("applicant_insights", Map.of("applicationCode", code(hidden))),
                toolCall("job_insights", Map.of("jobId", job.getId())),
                answer("Here are the applicants.", List.of(candidates,
                        Map.of("kind", "link", "label", "Open job", "href", "/jobs/" + job.getId()),
                        Map.of("kind", "link", "label", "Evil", "href", "https://evil.example/phish"),
                        Map.of("kind", "job_preview", "draft", Map.of()))));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        JsonNode data = send(id, "manager@a.test", "HR_MANAGER",
                "How do Rahul Verstappen (rahul@cand.test) and " + code(hidden) + " compare?",
                "{\"page\":\"applicant\",\"jobId\":" + job.getId() + ",\"applicationId\":" + hidden.getId() + "}");

        List<AiAssistantStepRequest> requests = requests(3);
        AiAssistantStepRequest first = requests.get(0);
        assertEquals("applicant", first.context().get("page"));
        assertEquals(job.getId(), ((Number) first.context().get("jobId")).longValue());
        assertEquals("Backend Engineer", first.context().get("jobTitle"));
        assertEquals(code(hidden), first.context().get("applicationCode"));
        assertTrue(first.observations().isEmpty());
        assertNull(first.draft());
        assertFalse(first.allowExternalModel());

        Map<String, Object> observation = requests.get(1).observations().get(0);
        assertEquals("applicant_insights", observation.get("tool"));
        assertEquals(true, observation.get("ok"));
        Map<?, ?> insight = (Map<?, ?>) observation.get("data");
        assertEquals(code(hidden), insight.get("code"));
        assertEquals("SUBMITTED", String.valueOf(insight.get("status")));
        Map<String, Object> jobObservation = requests.get(2).observations().get(1);
        assertEquals(true, jobObservation.get("ok"));
        assertEquals(2, ((Number) ((Map<?, ?>) jobObservation.get("data")).get("applicants")).intValue());

        for (AiAssistantStepRequest request : requests) {
            String json = mapper.writeValueAsString(request);
            for (String secret : List.of("Priya", "Sharmaville", "priya@cand.test", "Rahul", "Verstappen",
                    "rahul@cand.test", "98765", "github.com", "Zed", "Outsider")) {
                assertFalse(json.contains(secret), "planner input contains " + secret + ": " + json);
            }
        }
        String evidence = mapper.writeValueAsString(insight.get("requirements"));
        assertTrue(evidence.contains("[name]") && evidence.contains("[email]") && evidence.contains("[link]")
                && evidence.contains("[phone]"), evidence);
        assertTrue(requests.get(0).messages().get(0).text().contains("[name]"));

        JsonNode reply = data.path("messages").get(1);
        assertEquals("assistant", reply.path("role").asText());
        assertEquals("offline", reply.path("planner").asText());
        assertTrue(reply.path("status").asText().contains("Looking it up"));
        JsonNode blocks = reply.path("blocks");
        assertEquals(2, blocks.size(), blocks.toString());
        JsonNode items = blocks.get(0).path("items");
        assertEquals("candidates", blocks.get(0).path("kind").asText());
        assertEquals(2, items.size(), "the other organisation's applicant is dropped");
        assertEquals("Rahul Verstappen", items.get(0).path("revealedName").asText());
        assertEquals("SHORTLISTED", items.get(0).path("status").asText());
        assertEquals("/jobs/" + job.getId() + "/applicants/" + revealed.getId(), items.get(0).path("link").asText());
        assertTrue(items.get(1).path("revealedName").isNull(), "blind review keeps the name hidden");
        assertEquals("link", blocks.get(1).path("kind").asText());
        assertEquals("/jobs/" + job.getId(), blocks.get(1).path("href").asText());
    }

    @Test
    void aProposedJobIsCreatedAsADraftOnlyAfterConfirmationAndAudited() throws Exception {
        when(aiServiceClient.compileRequirements(any())).thenReturn(new AiCompileResponse("rules", List.of(),
                List.of(new AiCompileResponse.Warning("rockstar", "EXCLUSIONARY", "Use 'experienced'"))));
        Map<String, Object> draft = Map.of("kind", "job_posting", "fields", Map.of("title", "Senior Data Analyst"));
        when(aiServiceClient.assistantStep(any())).thenReturn(
                ask("Which location?", "location", List.of("Pune", "Remote"), draft),
                propose("create_job", jobArgs(), "Here is the posting. Save it as a draft or publish it?", draft));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        JsonNode first = send(id, "manager@a.test", "HR_MANAGER", "Post a job for a senior data analyst", null);
        JsonNode question = first.path("messages").get(1).path("blocks").get(0);
        assertEquals("question", question.path("kind").asText());
        assertEquals("location", question.path("field").asText());
        assertEquals("Pune", question.path("options").get(0).asText());

        long jobsBefore = jobs.count();
        JsonNode second = send(id, "manager@a.test", "HR_MANAGER", "Pune", null);
        AiAssistantStepRequest request = lastRequest();
        assertEquals("job_posting", request.draft().get("kind"), "the draft is kept by the backend between messages");
        JsonNode blocks = second.path("messages").get(1).path("blocks");
        assertEquals("job_preview", blocks.get(0).path("kind").asText());
        assertEquals("rockstar", blocks.get(0).path("draft").path("warnings").get(0).path("phrase").asText());
        assertEquals("SQL", blocks.get(0).path("draft").path("requirements").get(0).path("description").asText());
        JsonNode action = blocks.get(1);
        assertEquals("action", action.path("kind").asText());
        assertEquals("create_job", action.path("action").asText());
        assertEquals("PENDING", action.path("status").asText());
        assertTrue(action.path("expiresAt").asText().endsWith("Z"));
        assertEquals(jobsBefore, jobs.count(), "nothing is created while planning");
        long actionId = action.path("actionId").asLong();

        // Somebody else cannot confirm it.
        mvc.perform(post(BASE + "/actions/{id}/confirm", actionId).with(staff("manager2@a.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());

        MvcResult confirmed = mvc.perform(post(BASE + "/actions/{id}/confirm", actionId)
                        .with(staff("manager@a.test", "HR_MANAGER")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.result.kind", is("job")))
                .andExpect(jsonPath("$.data.message.role", is("assistant")))
                .andExpect(jsonPath("$.data.message.text", containsString("as a draft")))
                .andReturn();
        long jobId = data(confirmed).path("result").path("id").asLong();
        assertEquals("/jobs/" + jobId, data(confirmed).path("result").path("link").asText());
        Job created = jobs.findById(jobId).orElseThrow();
        assertEquals(JobStatus.DRAFT, created.getStatus());
        assertEquals(orgA.getId(), created.getOrganizationId());
        assertEquals("Pune", created.getLocation());
        assertTrue(created.getDescription().contains("Responsibilities:"));
        assertEquals(1, requirements.countByJobId(jobId));
        List<AuditEvent> audits = auditEvents.findAllByActionOrderByIdAsc(AuditActions.ASSISTANT_ACTION_CONFIRMED);
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).getDetails().startsWith("via=ASSISTANT"), audits.get(0).getDetails());
        assertTrue(audits.get(0).getDetails().contains("mode=DRAFT"));
        assertEquals(String.valueOf(jobId), audits.get(0).getTargetId());
        assertNull(jdbcTemplate.queryForObject("SELECT draft_json FROM assistant_conversations WHERE id = ?", String.class, id),
                "the draft is cleared once the job exists");

        // A second confirm is a conflict and creates nothing.
        mvc.perform(post(BASE + "/actions/{id}/confirm", actionId).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isConflict());
        assertEquals(jobsBefore + 1, jobs.count());
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()", is(5)))
                .andExpect(jsonPath("$.data.messages[3].blocks[1].status", is("CONFIRMED")));
    }

    @Test
    void confirmingWithModePublishPublishesTheJob() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(propose("create_job", jobArgs(), null, null));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        JsonNode data = send(id, "manager@a.test", "HR_MANAGER", "Post it", null);
        JsonNode preview = data.path("messages").get(1).path("blocks").get(0).path("draft");
        assertEquals("The wording check is unavailable right now; review the text yourself.",
                preview.path("compilerNote").asText(), "the compiler being unavailable does not block the preview");
        long actionId = data.path("messages").get(1).path("blocks").get(1).path("actionId").asLong();
        MvcResult confirmed = mvc.perform(post(BASE + "/actions/{id}/confirm", actionId)
                        .with(staff("manager@a.test", "HR_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PUBLISH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("CONFIRMED")))
                .andReturn();
        Job created = jobs.findById(data(confirmed).path("result").path("id").asLong()).orElseThrow();
        assertEquals(JobStatus.PUBLISHED, created.getStatus());
        assertNotNull(created.getPublishedAt());
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(AuditActions.ASSISTANT_ACTION_CONFIRMED).get(0)
                .getDetails().contains("mode=PUBLISH"));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc("JOB_STATUS_CHANGED").size());

        mvc.perform(post(BASE + "/actions/{id}/confirm", actionId).with(staff("manager@a.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SOMETHING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredAndCancelledActionsCannotBeConfirmed() throws Exception {
        Map<String, Object> args = Map.of("applicationCode", code(hidden), "decision", "SHORTLIST");
        when(aiServiceClient.assistantStep(any())).thenReturn(propose("decide_application", args, null, null));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        long expiring = proposedActionId(send(id, "manager@a.test", "HR_MANAGER", "Shortlist " + code(hidden), null));
        long cancelling = proposedActionId(send(id, "manager@a.test", "HR_MANAGER", "Shortlist " + code(hidden), null));

        jdbcTemplate.update("UPDATE assistant_actions SET expires_at = ? WHERE id = ?",
                Timestamp.valueOf(UtcTime.now().minusMinutes(1)), expiring);
        mvc.perform(post(BASE + "/actions/{id}/confirm", expiring).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isConflict());
        assertEquals("EXPIRED", jdbcTemplate.queryForObject("SELECT status FROM assistant_actions WHERE id = ?",
                String.class, expiring));

        mvc.perform(post(BASE + "/actions/{id}/cancel", cancelling).with(staff("manager2@a.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/actions/{id}/cancel", cancelling).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("CANCELLED")))
                .andExpect(jsonPath("$.data.action.action", is("decide_application")));
        mvc.perform(post(BASE + "/actions/{id}/confirm", cancelling).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/actions/{id}/cancel", cancelling).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isConflict());

        assertEquals(ApplicationStatus.SUBMITTED, applications.findById(hidden.getId()).orElseThrow().getStatus());
        mvc.perform(get(BASE + "/conversations/{id}", id).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[1].blocks[0].status", is("EXPIRED")))
                .andExpect(jsonPath("$.data.messages[3].blocks[0].status", is("CANCELLED")));
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(AuditActions.ASSISTANT_ACTION_CONFIRMED).isEmpty());
    }

    @Test
    void decisionsAreRecheckedOnConfirm() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(
                propose("decide_application", Map.of("applicationCode", code(hidden), "decision", "REJECT"), null, null),
                propose("decide_application", Map.of("applicationCode", code(hidden), "decision", "REJECT",
                        "reason", "Missing must-haves"), null, null),
                propose("decide_application", Map.of("applicationCode", code(revealed), "decision", "MOVE_TO_INTERVIEW"),
                        null, null));
        long id = createConversation("manager@a.test", "HR_MANAGER");

        JsonNode noReason = send(id, "manager@a.test", "HR_MANAGER", "Reject " + code(hidden), null);
        JsonNode notice = noReason.path("messages").get(1).path("blocks").get(0);
        assertEquals("notice", notice.path("kind").asText());
        assertTrue(notice.path("text").asText().contains("reason is required"), notice.toString());
        assertEquals(0, count("SELECT COUNT(*) FROM assistant_actions"));

        long reject = proposedActionId(send(id, "manager@a.test", "HR_MANAGER", "Reject " + code(hidden), null));
        // The application changes in the portal before the user confirms.
        screening.withStatus(applications.findById(hidden.getId()).orElseThrow(), ApplicationStatus.REJECTED);
        mvc.perform(post(BASE + "/actions/{id}/confirm", reject).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("FAILED")))
                .andExpect(jsonPath("$.data.result.kind", is("failed")))
                .andExpect(jsonPath("$.data.result.error", containsString("REJECTED")))
                .andExpect(jsonPath("$.data.message.blocks[0].kind", is("notice")));
        mvc.perform(post(BASE + "/actions/{id}/confirm", reject).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isConflict());
        assertTrue(auditEvents.findAllByActionOrderByIdAsc(AuditActions.ASSISTANT_ACTION_CONFIRMED).isEmpty());

        long move = proposedActionId(send(id, "manager@a.test", "HR_MANAGER", "Interview " + code(revealed), null));
        mvc.perform(post(BASE + "/actions/{id}/confirm", move).with(staff("manager@a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.result.kind", is("application")))
                .andExpect(jsonPath("$.data.result.link", is("/jobs/" + job.getId() + "/applicants/" + revealed.getId())));
        assertEquals(ApplicationStatus.INTERVIEWING, applications.findById(revealed.getId()).orElseThrow().getStatus());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.APPLICATION_DECISION).size());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.ASSISTANT_ACTION_CONFIRMED).size());
    }

    @Test
    void aConfirmIsRefusedWhenTheUserLostThePermission() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(
                propose("decide_application", Map.of("applicationCode", code(revealed), "decision", "MOVE_TO_INTERVIEW"),
                        null, null));
        long id = createConversation("manager2@a.test", "HR_MANAGER");
        long actionId = proposedActionId(send(id, "manager2@a.test", "HR_MANAGER", "Interview " + code(revealed), null));
        User user = users.findByEmail("manager2@a.test").orElseThrow();
        user.setRole(Role.RECRUITER);
        users.save(user);
        mvc.perform(post(BASE + "/actions/{id}/confirm", actionId).with(staff("manager2@a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action.status", is("FAILED")));
        assertEquals(ApplicationStatus.SHORTLISTED, applications.findById(revealed.getId()).orElseThrow().getStatus());
    }

    @Test
    void messagesAreLimitedPerUserAndHour() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(answer("ok", List.of()));
        long id = createConversation("recruiter@a.test", "RECRUITER");
        for (int i = 0; i < 30; i++) {
            send(id, "recruiter@a.test", "RECRUITER", "Message " + i, null);
        }
        mvc.perform(post(BASE + "/conversations/{id}/messages", id).with(staff("recruiter@a.test", "RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"one more\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertEquals(60, count("SELECT COUNT(*) FROM assistant_messages"), "the refused message is not stored");
        // Another user is not affected.
        long other = createConversation("manager@a.test", "HR_MANAGER");
        send(other, "manager@a.test", "HR_MANAGER", "hello", null);
    }

    @Test
    void anUnavailablePlannerGivesAnOfflineSafeNotice() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenThrow(new ResourceAccessException("connection refused"));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        JsonNode reply = send(id, "manager@a.test", "HR_MANAGER", "How is the job doing?",
                "{\"page\":\"job\",\"jobId\":" + job.getId() + "}").path("messages").get(1);
        assertEquals("unavailable", reply.path("planner").asText());
        assertTrue(reply.path("text").asText().contains("unavailable"));
        JsonNode blocks = reply.path("blocks");
        assertEquals("notice", blocks.get(0).path("kind").asText());
        assertEquals("warning", blocks.get(0).path("tone").asText());
        List<String> links = new ArrayList<>();
        for (JsonNode block : blocks) {
            if ("link".equals(block.path("kind").asText())) links.add(block.path("href").asText());
        }
        assertTrue(links.contains("/jobs/" + job.getId()), links.toString());
        assertTrue(links.contains("/jobs"));

        when(aiServiceClient.assistantStep(any())).thenReturn(null);
        JsonNode again = send(id, "manager@a.test", "HR_MANAGER", "Hello?", null).path("messages").get(1);
        assertEquals("unavailable", again.path("planner").asText());
    }

    @Test
    void thePlannerGetsAtMostSixStepsPerMessage() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(toolCall("explain", Map.of("topic", "verified_coverage")));
        long id = createConversation("interviewer@a.test", "INTERVIEWER");
        JsonNode reply = send(id, "interviewer@a.test", "INTERVIEWER", "Explain", null).path("messages").get(1);
        verify(aiServiceClient, times(6)).assistantStep(any());
        assertTrue(reply.path("text").asText().contains("6 steps"), reply.toString());
        List<AiAssistantStepRequest> requests = requests(6);
        Map<String, Object> observation = requests.get(5).observations().get(4);
        assertEquals(true, observation.get("ok"));
        assertEquals("VERIFIED_COVERAGE", ((Map<?, ?>) observation.get("args")).get("topic"));
    }

    @Test
    void theOrganisationSettingDecidesWhetherTheExternalModelMayBeUsed() throws Exception {
        when(aiServiceClient.assistantStep(any())).thenReturn(answer("ok", List.of()));
        long id = createConversation("manager@a.test", "HR_MANAGER");
        send(id, "manager@a.test", "HR_MANAGER", "hi", null);
        assertFalse(lastRequest().allowExternalModel(), "new organisations default to the offline planner");

        mvc.perform(patch("/api/v1/admin/organization").with(staff("admin@a.test", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assistantExternalModel\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantExternalModel", is(true)));
        send(id, "manager@a.test", "HR_MANAGER", "hi again", null);
        assertTrue(lastRequest().allowExternalModel());

        Organization organization = organizations.findById(orgA.getId()).orElseThrow();
        organization.setAssistantExternalModel(false);
        organizations.save(organization);
        send(id, "manager@a.test", "HR_MANAGER", "and again", null);
        assertFalse(lastRequest().allowExternalModel());
    }

    @Test
    void retentionPurgesConversationsInactiveForMoreThan180Days() {
        Long userId = manager.getUser().getId();
        long old = conversation(userId, UtcTime.now().minusDays(200));
        long recent = conversation(userId, UtcTime.now().minusDays(10));
        jdbcTemplate.update("INSERT INTO assistant_messages (conversation_id, role, text, blocks_json, created_at) "
                + "VALUES (?, 'user', 'old message', '[]', ?)", old, Timestamp.valueOf(UtcTime.now().minusDays(200)));
        jdbcTemplate.update("INSERT INTO assistant_actions (conversation_id, user_id, organization_id, action, args_json, "
                        + "status, expires_at, created_at, updated_at) VALUES (?, ?, ?, 'explain', '{}', 'EXPIRED', ?, ?, ?)",
                old, userId, orgA.getId(), ts(200), ts(200), ts(200));

        RetentionReport report = retentionPurgeService.purge();

        assertEquals(1, report.count("assistant_conversations"));
        assertEquals(List.of(recent), jdbcTemplate.queryForList("SELECT id FROM assistant_conversations", Long.class));
        assertEquals(0, count("SELECT COUNT(*) FROM assistant_messages"));
        assertEquals(0, count("SELECT COUNT(*) FROM assistant_actions"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Application applicant(Job target, String email, String first, String last, int minutes, String evidence) {
        CandidateProfile candidate = fixtures.candidate(email);
        User user = candidate.getUser();
        user.setFirstName(first);
        user.setLastName(last);
        users.save(user);
        Resume resume = screening.resume(candidate, "resume-" + first + ".pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, target, resume, T0.plusMinutes(minutes));
        List<JobRequirement> jobRequirements = requirements.findByJobIdOrderByDisplayOrderAscIdAsc(target.getId());
        if (!jobRequirements.isEmpty()) {
            Long requirementId = jobRequirements.get(0).getId();
            screening.currentRun(application, analysis, 100.0, 100.0, true,
                    List.of(new AiMatchResponse.RequirementResult(requirementId, "MATCHED", "RESUME_STATED", evidence,
                            List.of("fact:1"), "skills", "RESUME", "Matched.", "Ask " + first + " about scale")),
                    List.of());
        }
        return application;
    }

    private Map<String, Object> jobArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", "Senior Data Analyst");
        args.put("department", "Analytics");
        args.put("location", "Pune");
        args.put("jobType", "full_time");
        args.put("experienceLevel", "SENIOR");
        args.put("openings", 2);
        args.put("summary", "Own the analytics of our hiring funnel.");
        args.put("responsibilities", List.of("Build dashboards", "Partner with HR"));
        args.put("requirements", List.of(Map.of("type", "SKILL", "description", "SQL", "mandatory", true,
                "knockout", true, "minimumYears", 3)));
        args.put("unexpected", "ignored");
        return args;
    }

    private long conversation(Long userId, LocalDateTime updatedAt) {
        jdbcTemplate.update("INSERT INTO assistant_conversations (user_id, organization_id, title, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)", userId, orgA.getId(), "c-" + updatedAt, Timestamp.valueOf(updatedAt),
                Timestamp.valueOf(updatedAt));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM assistant_conversations", Long.class);
    }

    private static Timestamp ts(int daysAgo) {
        return Timestamp.valueOf(UtcTime.now().minusDays(daysAgo));
    }

    private List<String> catalogue(String email, String role) throws Exception {
        long id = createConversation(email, role);
        send(id, email, role, "What can you do?", null);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : lastRequest().tools()) {
            names.add(String.valueOf(tool.get("name")));
        }
        return names;
    }

    private AiAssistantStepRequest lastRequest() {
        ArgumentCaptor<AiAssistantStepRequest> captor = ArgumentCaptor.forClass(AiAssistantStepRequest.class);
        verify(aiServiceClient, atLeastOnce()).assistantStep(captor.capture());
        return captor.getValue();
    }

    private List<AiAssistantStepRequest> requests(int expected) {
        ArgumentCaptor<AiAssistantStepRequest> captor = ArgumentCaptor.forClass(AiAssistantStepRequest.class);
        verify(aiServiceClient, times(expected)).assistantStep(captor.capture());
        return captor.getAllValues();
    }

    private long createConversation(String email, String role) throws Exception {
        MvcResult result = mvc.perform(post(BASE + "/conversations").with(staff(email, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andReturn();
        return data(result).path("id").asLong();
    }

    private JsonNode send(long conversationId, String email, String role, String text, String contextJson) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        if (contextJson != null) {
            body.put("context", mapper.readValue(contextJson, Map.class));
        }
        MvcResult result = mvc.perform(post(BASE + "/conversations/{id}/messages", conversationId).with(staff(email, role))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()", is(2)))
                .andExpect(jsonPath("$.data.messages[0].role", is("user")))
                .andReturn();
        return data(result);
    }

    private static long proposedActionId(JsonNode data) {
        for (JsonNode block : data.path("messages").get(1).path("blocks")) {
            if ("action".equals(block.path("kind").asText())) {
                assertEquals("PENDING", block.path("status").asText());
                return block.path("actionId").asLong();
            }
        }
        throw new AssertionError("No action block in " + data);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String code(Application application) {
        return "A-" + application.getId();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }

    private static Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("planner", "offline");
        meta.put("model", null);
        meta.put("fallback", false);
        return meta;
    }

    private static AiAssistantStepResponse toolCall(String tool, Map<String, Object> args) {
        return new AiAssistantStepResponse("tool_call", tool, args, "Looking it up", null, null, null, null, null, null,
                null, meta());
    }

    private static AiAssistantStepResponse answer(String text, List<Map<String, Object>> blocks) {
        return new AiAssistantStepResponse("answer", null, null, null, text, null, null, null, null, null, blocks, meta());
    }

    private static AiAssistantStepResponse ask(String text, String field, List<Object> options, Map<String, Object> draft) {
        return new AiAssistantStepResponse("ask", null, null, null, text, field, options, draft, null, null, null, meta());
    }

    private static AiAssistantStepResponse propose(String action, Map<String, Object> args, String summary,
                                                   Map<String, Object> draft) {
        return new AiAssistantStepResponse("propose", null, args, null, null, null, null, draft, action, summary, null,
                meta());
    }
}
