package com.recruitpro.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.interview.service.InterviewEvidenceContributor;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Interview kits, scheduling, interviewer views and scorecards (plan 5.6, WP-BE-C): kit 404 until saved, scheduling
 * rules (status, interviewer of the same organisation, future time) and the automatic MOVE_TO_INTERVIEW, who may
 * open and score an interview, INTERVIEW_VERIFIED evidence, what the candidate sees, reschedule/cancel and tenancy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewIntegrationTest {

    private static final String KIT = "/api/v1/hr/jobs/{jobId}/interview-kit";
    private static final String SCHEDULE = "/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews";
    private static final String INTERVIEW = "/api/v1/interviews/{id}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationDecisionHistoryRepository history;
    @Autowired InterviewRepository interviews;
    @Autowired AuditEventRepository auditEvents;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobService backgroundJobService;
    @Autowired MatchingService matchingService;

    Job job;
    Job otherJob;
    JobRequirement java;
    JobRequirement docker;
    JobRequirement sql;
    JobRequirement otherRequirement;
    Application shortlisted;
    Application submitted;
    User interviewer;
    User interviewer2;
    User disabledInterviewer;
    User interviewerB;
    User candidateUser;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization orgA = fixtures.organization("Interview Org A");
        Organization orgB = fixtures.organization("Interview Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@iv-a.test", Role.HR_MANAGER);
        fixtures.staff(orgA, "recruiter@iv-a.test", Role.RECRUITER);
        interviewer = fixtures.staff(orgA, "interviewer@iv-a.test", Role.INTERVIEWER);
        interviewer2 = fixtures.staff(orgA, "interviewer2@iv-a.test", Role.INTERVIEWER);
        disabledInterviewer = fixtures.staff(orgA, "disabled@iv-a.test", Role.INTERVIEWER,
                TestDataFactory.UNUSABLE_PASSWORD_HASH, false);
        HRProfile managerB = fixtures.hrMember(orgB, "manager@iv-b.test", Role.HR_MANAGER);
        interviewerB = fixtures.staff(orgB, "interviewer@iv-b.test", Role.INTERVIEWER);
        job = fixtures.job(manager, "Data Engineer", JobStatus.PUBLISHED);
        otherJob = fixtures.job(managerB, "Other Org Job", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        docker = screening.requirement(job, RequirementType.SKILL, "Docker", false, false);
        sql = screening.requirement(job, RequirementType.SKILL, "SQL", true, false);
        otherRequirement = screening.requirement(otherJob, RequirementType.SKILL, "Go", true, true);

        CandidateProfile candidate = fixtures.candidate("cand@iv-cand.test");
        candidateUser = candidate.getUser();
        Resume resume = screening.resume(candidate, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(2));
        application.setIdentityRevealedAt(UtcTime.now().minusDays(1));
        shortlisted = screening.withStatus(application, ApplicationStatus.SHORTLISTED);
        screening.currentRun(shortlisted, analysis, 40.0, 60.0, true,
                List.of(ScreeningFixtures.matched(java.getId(), "RESUME_STATED", "RESUME"),
                        ScreeningFixtures.notObserved(docker.getId()),
                        ScreeningFixtures.matched(sql.getId(), "SELF_CLAIMED", "CANDIDATE_CLAIM")),
                List.of());

        CandidateProfile other = fixtures.candidate("other@iv-cand.test");
        Resume otherResume = screening.resume(other, "other.pdf");
        screening.analysis(otherResume, ResumeAnalysisStatus.COMPLETED);
        submitted = screening.application(other, job, otherResume, UtcTime.now().minusDays(1));
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void theKitIsMissingUntilSavedAndOnlyManagersEditIt() throws Exception {
        var manager = staff("manager@iv-a.test", "HR_MANAGER");
        var recruiter = staff("recruiter@iv-a.test", "RECRUITER");
        mvc.perform(get(KIT, job.getId()).with(recruiter)).andExpect(status().isNotFound());

        String kit = "{\"questions\":[" + question("q1", java.getId(), "Tell me about Java") + ","
                + question("", null, "How do you communicate?") + "]}";
        mvc.perform(put(KIT, job.getId()).with(recruiter).contentType(MediaType.APPLICATION_JSON).content(kit))
                .andExpect(status().isForbidden());
        mvc.perform(put(KIT, job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON).content(kit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId", is(job.getId().intValue())))
                .andExpect(jsonPath("$.data.generatedBy", is("manual")))
                .andExpect(jsonPath("$.data.questions.length()", is(2)))
                .andExpect(jsonPath("$.data.questions[0].requirementId", is(java.getId().intValue())))
                .andExpect(jsonPath("$.data.questions[1].id", is("q2")))
                .andExpect(jsonPath("$.data.questions[1].requirementId", nullValue()));
        mvc.perform(get(KIT, job.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].anchors['3']", is("good")));

        String foreign = "{\"questions\":[" + question("q1", otherRequirement.getId(), "Go?") + "]}";
        mvc.perform(put(KIT, job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON).content(foreign))
                .andExpect(status().isBadRequest());
        String duplicate = "{\"questions\":[" + question("q1", null, "A?") + "," + question("q1", null, "B?") + "]}";
        mvc.perform(put(KIT, job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest());
        String noAnchors = "{\"questions\":[{\"id\":\"q1\",\"requirementId\":null,\"question\":\"A?\",\"followUps\":[],"
                + "\"anchors\":{\"1\":\"a\"}}]}";
        mvc.perform(put(KIT, job.getId()).with(manager).contentType(MediaType.APPLICATION_JSON).content(noAnchors))
                .andExpect(status().isBadRequest());

        mvc.perform(get(KIT, job.getId()).with(staff("interviewer@iv-a.test", "INTERVIEWER"))).andExpect(status().isForbidden());
        var other = staff("manager@iv-b.test", "HR_MANAGER");
        mvc.perform(get(KIT, job.getId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(put(KIT, job.getId()).with(other).contentType(MediaType.APPLICATION_JSON).content(kit))
                .andExpect(status().isNotFound());
        mvc.perform(post(KIT + "/generate", job.getId()).with(other)).andExpect(status().isNotFound());
    }

    @Test
    void schedulingChecksTheApplicationAndInterviewerAndMovesTheApplicationToInterviewing() throws Exception {
        var manager = staff("manager@iv-a.test", "HR_MANAGER");
        String later = UtcTime.format(UtcTime.now().plusDays(2));
        mvc.perform(schedule(manager, submitted, interviewer.getId(), later)).andExpect(status().isBadRequest());
        mvc.perform(schedule(manager, shortlisted, interviewerB.getId(), later)).andExpect(status().isBadRequest());
        mvc.perform(schedule(manager, shortlisted, disabledInterviewer.getId(), later)).andExpect(status().isBadRequest());
        mvc.perform(schedule(manager, shortlisted, candidateUser.getId(), later)).andExpect(status().isBadRequest());
        mvc.perform(schedule(manager, shortlisted, interviewer.getId(), UtcTime.format(UtcTime.now().minusHours(1))))
                .andExpect(status().isBadRequest());
        mvc.perform(schedule(staff("recruiter@iv-a.test", "RECRUITER"), shortlisted, interviewer.getId(), later))
                .andExpect(status().isForbidden());
        mvc.perform(schedule(staff("manager@iv-b.test", "HR_MANAGER"), shortlisted, interviewerB.getId(), later))
                .andExpect(status().isNotFound());
        assertEquals(ApplicationStatus.SHORTLISTED, applications.findById(shortlisted.getId()).orElseThrow().getStatus());
        assertEquals(0, interviews.count());

        mvc.perform(schedule(manager, shortlisted, interviewer.getId(), later))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.data.applicationId", is(shortlisted.getId().intValue())))
                .andExpect(jsonPath("$.data.jobId", is(job.getId().intValue())))
                .andExpect(jsonPath("$.data.jobTitle", is("Data Engineer")))
                .andExpect(jsonPath("$.data.candidateName", is("Test Candidate")))
                .andExpect(jsonPath("$.data.interviewerUserId", is(interviewer.getId().intValue())))
                .andExpect(jsonPath("$.data.interviewerName", is("Test User")))
                .andExpect(jsonPath("$.data.durationMinutes", is(45)))
                .andExpect(jsonPath("$.data.location", is("Room 4")))
                .andExpect(jsonPath("$.data.scorecard", nullValue()));
        assertEquals(ApplicationStatus.INTERVIEWING, applications.findById(shortlisted.getId()).orElseThrow().getStatus());
        List<ApplicationDecisionHistory> events = history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(shortlisted.getId());
        assertEquals(1, events.stream().filter(e -> e.getActionType() == ApplicationDecisionAction.MOVE_TO_INTERVIEW).count());
        assertNotNull(events.get(events.size() - 1).getMatchRunId(), "the decision records the current match run");
        assertEquals(1, emails("cand@iv-cand.test", NotificationTemplate.INTERVIEW_SCHEDULED));
        assertEquals(1, emails("cand@iv-cand.test", NotificationTemplate.APPLICATION_STATUS_CHANGED));
        assertEquals(1, emails("interviewer@iv-a.test", NotificationTemplate.INTERVIEW_ASSIGNED));

        // A second interview keeps INTERVIEWING and adds no decision.
        mvc.perform(schedule(manager, shortlisted, interviewer2.getId(), later)).andExpect(status().isOk());
        assertEquals(1, history.findAllByApplicationIdOrderByCreatedAtAscIdAsc(shortlisted.getId()).stream()
                .filter(e -> e.getActionType() == ApplicationDecisionAction.MOVE_TO_INTERVIEW).count());

        var recruiter = staff("recruiter@iv-a.test", "RECRUITER");
        mvc.perform(get(SCHEDULE, job.getId(), shortlisted.getId()).with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(2)));
        mvc.perform(get(SCHEDULE, job.getId(), shortlisted.getId()).with(staff("manager@iv-b.test", "HR_MANAGER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).param("status", "INTERVIEWING").with(recruiter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].interviewCount", is(2)));
    }

    @Test
    void onlyTheAssignedInterviewerScoresAndHighRatingsBecomeInterviewVerifiedEvidence() throws Exception {
        long id = scheduleOne();
        String kit = "{\"questions\":[" + question("q1", java.getId(), "Tell me about Java") + ","
                + question("q2", docker.getId(), "Tell me about Docker") + "]}";
        mvc.perform(put(KIT, job.getId()).with(staff("manager@iv-a.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(kit)).andExpect(status().isOk());

        var assigned = staff("interviewer@iv-a.test", "INTERVIEWER");
        mvc.perform(get("/api/v1/interviews/mine").with(assigned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].id", is((int) id)));
        mvc.perform(get("/api/v1/interviews/mine").with(staff("interviewer2@iv-a.test", "INTERVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(0)));
        mvc.perform(get("/api/v1/interviews/mine").with(SecurityMockMvcRequestPostProcessors.user("cand@iv-cand.test").roles("CANDIDATE")))
                .andExpect(status().isForbidden());

        mvc.perform(get(INTERVIEW, id).with(assigned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kit.length()", is(2)))
                .andExpect(jsonPath("$.data.focusRequirements[*].requirementId",
                        contains(sql.getId().intValue(), docker.getId().intValue(), java.getId().intValue())));
        mvc.perform(get(INTERVIEW, id).with(staff("manager@iv-a.test", "HR_MANAGER"))).andExpect(status().isOk());
        mvc.perform(get(INTERVIEW, id).with(staff("interviewer2@iv-a.test", "INTERVIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(INTERVIEW, id).with(staff("recruiter@iv-a.test", "RECRUITER"))).andExpect(status().isForbidden());
        mvc.perform(get(INTERVIEW, id).with(staff("manager@iv-b.test", "HR_MANAGER"))).andExpect(status().isNotFound());
        mvc.perform(get(INTERVIEW, id).with(staff("interviewer@iv-b.test", "INTERVIEWER"))).andExpect(status().isNotFound());

        String valid = "{\"ratings\":[" + rating("q1", java.getId(), 4, "NOTES-SECRET") + ","
                + rating("q2", docker.getId(), 2, null) + "],\"recommendation\":\"YES\",\"summary\":\"SUMMARY-SECRET\"}";
        mvc.perform(scorecard(staff("manager@iv-a.test", "HR_MANAGER"), id, valid)).andExpect(status().isForbidden());
        mvc.perform(scorecard(staff("interviewer2@iv-a.test", "INTERVIEWER"), id, valid)).andExpect(status().isForbidden());
        mvc.perform(scorecard(staff("manager@iv-b.test", "HR_MANAGER"), id, valid)).andExpect(status().isNotFound());
        mvc.perform(scorecard(assigned, id, "{\"ratings\":[" + rating("q1", java.getId(), 5, null)
                + "],\"recommendation\":\"YES\"}")).andExpect(status().isBadRequest());
        mvc.perform(scorecard(assigned, id, "{\"ratings\":[" + rating("q1", otherRequirement.getId(), 4, null)
                + "],\"recommendation\":\"YES\"}")).andExpect(status().isBadRequest());
        mvc.perform(scorecard(assigned, id, "{\"ratings\":[],\"recommendation\":\"MAYBE\"}")).andExpect(status().isBadRequest());

        mvc.perform(scorecard(assigned, id, valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.scorecard.recommendation", is("YES")))
                .andExpect(jsonPath("$.data.scorecard.ratings.length()", is(2)))
                .andExpect(jsonPath("$.data.scorecard.ratings[0].notes", is("NOTES-SECRET")));
        mvc.perform(scorecard(assigned, id, valid)).andExpect(status().isConflict());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SCORECARD_SUBMITTED).size());
        assertFalse(backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, shortlisted.getId()).isEmpty());

        PreparedMatch prepared = matchingService.prepare(shortlisted.getId());
        assertNotNull(prepared);
        List<AiMatchRequest.Fact> contributed = prepared.request().facts().stream()
                .filter(fact -> fact.id().startsWith("iv:")).toList();
        assertEquals(1, contributed.size(), "only the requirement-linked rating of 3 or more becomes evidence");
        assertEquals(InterviewEvidenceContributor.factId(id, java.getId()), contributed.get(0).id());
        assertEquals("iv:" + id + ":r" + java.getId(), contributed.get(0).id());
        assertEquals("INTERVIEW_VERIFIED", contributed.get(0).tier());
        assertFalse(contributed.get(0).evidence().contains("NOTES-SECRET"), "interviewer notes never reach matching");

        var candidate = SecurityMockMvcRequestPostProcessors.user("cand@iv-cand.test").roles("CANDIDATE");
        String mine = mvc.perform(get("/api/v1/candidates/me/interviews").with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].status", is("COMPLETED")))
                .andExpect(jsonPath("$.data[0].jobTitle", is("Data Engineer")))
                // Integration pass (R5#8): the candidate's interview names its application.
                .andExpect(jsonPath("$.data[0].applicationId", is(shortlisted.getId().intValue())))
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/v1/candidates/me/applications/{id}", shortlisted.getId()).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviews.length()", is(1)))
                .andReturn().getResponse().getContentAsString();
        for (String body : List.of(mine, detail)) {
            assertFalse(body.contains("NOTES-SECRET"));
            assertFalse(body.contains("SUMMARY-SECRET"));
            assertFalse(body.contains("recommendation"));
            assertFalse(body.contains("scorecard"));
            assertFalse(body.contains("interviewer@iv-a.test"));
        }
        JsonNode staffDetail = mapper.readTree(mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}",
                        job.getId(), shortlisted.getId()).with(staff("manager@iv-a.test", "HR_MANAGER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("YES", staffDetail.path("data").path("interviews").path(0).path("scorecard").path("recommendation").asText());
    }

    @Test
    void managersRescheduleAndCancelScheduledInterviews() throws Exception {
        long id = scheduleOne();
        var manager = staff("manager@iv-a.test", "HR_MANAGER");
        String later = UtcTime.format(UtcTime.now().plusDays(5));
        mvc.perform(patch(INTERVIEW, id).with(staff("recruiter@iv-a.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"durationMinutes\":60}")).andExpect(status().isForbidden());
        mvc.perform(patch(INTERVIEW, id).with(staff("manager@iv-b.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"durationMinutes\":60}")).andExpect(status().isNotFound());
        mvc.perform(patch(INTERVIEW, id).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledAt\":\"" + later + "\",\"durationMinutes\":60,\"location\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.durationMinutes", is(60)))
                .andExpect(jsonPath("$.data.location", nullValue()));
        assertEquals(2, emails("cand@iv-cand.test", NotificationTemplate.INTERVIEW_SCHEDULED));
        mvc.perform(patch(INTERVIEW, id).with(manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch(INTERVIEW, id).with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CANCELLED")));
        assertEquals(InterviewStatus.CANCELLED, interviews.findById(id).orElseThrow().getStatus());
        assertEquals(1, emails("cand@iv-cand.test", NotificationTemplate.INTERVIEW_CANCELLED));
        assertEquals(1, emails("interviewer@iv-a.test", NotificationTemplate.INTERVIEW_CANCELLED));
        mvc.perform(patch(INTERVIEW, id).with(manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMinutes\":30}")).andExpect(status().isBadRequest());
        mvc.perform(scorecard(staff("interviewer@iv-a.test", "INTERVIEWER"), id,
                "{\"ratings\":[],\"recommendation\":\"NO\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId()).param("status", "INTERVIEWING")
                        .with(staff("recruiter@iv-a.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].interviewCount", is(0)));
    }

    // ---- helpers ----

    private long scheduleOne() throws Exception {
        String body = mvc.perform(schedule(staff("manager@iv-a.test", "HR_MANAGER"), shortlisted, interviewer.getId(),
                        UtcTime.format(UtcTime.now().plusDays(2))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    private long emails(String recipient, NotificationTemplate template) {
        return notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(recipient, template).stream()
                .filter(n -> n.getChannel() == NotificationChannel.EMAIL).count();
    }

    private RequestBuilder schedule(SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor principal,
                                    Application application, Long interviewerId, String scheduledAt) {
        return post(SCHEDULE, job.getId(), application.getId()).with(principal).contentType(MediaType.APPLICATION_JSON)
                .content("{\"interviewerUserId\":" + interviewerId + ",\"scheduledAt\":\"" + scheduledAt
                        + "\",\"durationMinutes\":45,\"location\":\"Room 4\"}");
    }

    private RequestBuilder scorecard(SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor principal, long id,
                                     String body) {
        return post(INTERVIEW + "/scorecard", id).with(principal).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String question(String id, Long requirementId, String text) {
        return "{\"id\":\"" + id + "\",\"requirementId\":" + requirementId + ",\"question\":\"" + text
                + "\",\"followUps\":[\"What was your part?\"],\"anchors\":{\"1\":\"poor\",\"2\":\"weak\",\"3\":\"good\",\"4\":\"strong\"}}";
    }

    private static String rating(String questionId, Long requirementId, int rating, String notes) {
        return "{\"questionId\":\"" + questionId + "\",\"requirementId\":" + requirementId + ",\"rating\":" + rating
                + ",\"notes\":" + (notes == null ? "null" : "\"" + notes + "\"") + "}";
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
