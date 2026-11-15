package com.recruitpro.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobService;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.AuthTestSupport;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-PLAT interview additions: ICS invitations on scheduled, rescheduled and cancelled e-mails (stable UID, rising
 * SEQUENCE, METHOD REQUEST/CANCEL) and scorecard editing by its author until the application is decided.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewPlatformIntegrationTest {

    private static final String MANAGER = "manager@ivp.test";
    private static final String INTERVIEWER = "interviewer@ivp.test";
    private static final String INTERVIEWER2 = "interviewer2@ivp.test";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired InterviewRepository interviews;
    @Autowired NotificationRepository notifications;
    @Autowired AuditEventRepository auditEvents;
    @Autowired BackgroundJobService backgroundJobService;

    Job job;
    Application application;
    User interviewer;
    User candidateUser;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Interview Platform Org");
        HRProfile manager = fixtures.hrMember(organization, MANAGER, Role.HR_MANAGER);
        interviewer = fixtures.staff(organization, INTERVIEWER, Role.INTERVIEWER);
        fixtures.staff(organization, INTERVIEWER2, Role.INTERVIEWER);
        job = fixtures.job(manager, "Platform Engineer", JobStatus.PUBLISHED);
        CandidateProfile candidate = fixtures.candidate("cand@ivp-cand.test");
        candidateUser = candidate.getUser();
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application created = screening.application(candidate, job, resume, UtcTime.now().minusDays(2));
        created.setIdentityRevealedAt(UtcTime.now().minusDays(1));
        application = screening.withStatus(created, ApplicationStatus.SHORTLISTED);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void interviewEmailsCarryIcsInvitationsWithAStableUidAndRisingSequence() throws Exception {
        String when = UtcTime.format(UtcTime.now().plusDays(3).withNano(0));
        JsonNode created = AuthTestSupport.data(mapper, mvc.perform(post("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews",
                        job.getId(), application.getId()).with(user(MANAGER).roles("HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interviewerUserId\":" + interviewer.getId() + ",\"scheduledAt\":\"" + when
                                + "\",\"durationMinutes\":45,\"location\":\"Room 1, Building A\"}"))
                .andExpect(status().isOk()).andReturn());
        long interviewId = created.path("id").asLong();
        String uid = "UID:interview-" + interviewId + "@recruitpro.local";

        String candidateIcs = latestIcs(candidateUser.getEmail(), NotificationTemplate.INTERVIEW_SCHEDULED);
        String interviewerIcs = latestIcs(INTERVIEWER, NotificationTemplate.INTERVIEW_ASSIGNED);
        for (String ics : List.of(candidateIcs, interviewerIcs)) {
            String unfolded = ics.replace("\r\n ", "");
            assertTrue(unfolded.contains("\r\nMETHOD:REQUEST\r\n"));
            assertTrue(unfolded.contains("\r\n" + uid + "\r\n"));
            assertTrue(unfolded.contains("\r\nSEQUENCE:0\r\n"));
            assertTrue(unfolded.contains("LOCATION:Room 1\\, Building A"));
            assertTrue(unfolded.contains("ORGANIZER;CN=\"RecruitPro\":mailto:no-reply@recruitpro.local"));
            assertTrue(unfolded.contains("mailto:" + candidateUser.getEmail()), "identity revealed: candidate attends");
            assertTrue(unfolded.contains("mailto:" + INTERVIEWER));
        }
        for (Notification inbox : notifications.findAll()) {
            if (inbox.getChannel() == NotificationChannel.IN_APP) {
                assertNull(inbox.getAttachmentContent(), "inbox copies never carry the attachment");
            }
        }

        String later = UtcTime.format(UtcTime.now().plusDays(4).withNano(0));
        mvc.perform(patch("/api/v1/interviews/{id}", interviewId).with(user(MANAGER).roles("HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"" + later + "\"}"))
                .andExpect(status().isOk());
        String rescheduled = latestIcs(candidateUser.getEmail(), NotificationTemplate.INTERVIEW_SCHEDULED).replace("\r\n ", "");
        assertTrue(rescheduled.contains("\r\n" + uid + "\r\n"), "same event");
        assertTrue(rescheduled.contains("\r\nSEQUENCE:1\r\n"));
        assertTrue(rescheduled.contains("\r\nMETHOD:REQUEST\r\n"));

        mvc.perform(patch("/api/v1/interviews/{id}", interviewId).with(user(MANAGER).roles("HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());
        for (String email : List.of(candidateUser.getEmail(), INTERVIEWER)) {
            String cancel = latestIcs(email, NotificationTemplate.INTERVIEW_CANCELLED).replace("\r\n ", "");
            assertTrue(cancel.contains("\r\nMETHOD:CANCEL\r\n"));
            assertTrue(cancel.contains("\r\nSTATUS:CANCELLED\r\n"));
            assertTrue(cancel.contains("\r\nSEQUENCE:2\r\n"));
            assertTrue(cancel.contains("\r\n" + uid + "\r\n"));
        }
        Notification cancelMail = latest(INTERVIEWER, NotificationTemplate.INTERVIEW_CANCELLED);
        assertEquals("cancel.ics", cancelMail.getAttachmentName());
        assertEquals("text/calendar; charset=UTF-8; method=CANCEL", cancelMail.getAttachmentContentType());
    }

    @Test
    void theAuthorEditsTheScorecardUntilTheApplicationIsDecided() throws Exception {
        Interview interview = scheduledInterview();
        JsonNode submitted = AuthTestSupport.data(mapper, mvc.perform(post("/api/v1/interviews/{id}/scorecard", interview.getId())
                        .with(user(INTERVIEWER).roles("INTERVIEWER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratings\":[{\"questionId\":\"q1\",\"rating\":2,\"notes\":\"first\"}],"
                                + "\"recommendation\":\"NO\",\"summary\":\"Initial view\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scorecard.revisionCount", is(0)))
                .andReturn());
        long scorecardId = submitted.path("scorecard").path("id").asLong();
        int matchJobsBefore = backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, application.getId()).size();

        String edit = "{\"ratings\":[{\"questionId\":\"q1\",\"rating\":4,\"notes\":\"after review of my notes\"}],"
                + "\"recommendation\":\"STRONG_YES\",\"summary\":\"Revised\"}";
        mvc.perform(editScorecard(interview.getId(), edit).with(user(INTERVIEWER2).roles("INTERVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(editScorecard(interview.getId(), edit).with(user(MANAGER).roles("HR_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(editScorecard(interview.getId(), "{\"ratings\":[{\"questionId\":\"q1\",\"rating\":5}],"
                        + "\"recommendation\":\"YES\"}").with(user(INTERVIEWER).roles("INTERVIEWER")))
                .andExpect(status().isBadRequest());
        mvc.perform(editScorecard(interview.getId(), edit).with(user(INTERVIEWER).roles("INTERVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scorecard.id", is((int) scorecardId)))
                .andExpect(jsonPath("$.data.scorecard.recommendation", is("STRONG_YES")))
                .andExpect(jsonPath("$.data.scorecard.ratings[0].rating", is(4)))
                .andExpect(jsonPath("$.data.scorecard.revisionCount", is(1)))
                .andExpect(jsonPath("$.data.status", is("COMPLETED")));
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SCORECARD_UPDATED).size());
        assertTrue(backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, application.getId()).size() >= matchJobsBefore,
                "re-matching is queued (or already pending)");
        assertFalse(backgroundJobService.findJobs(BackgroundJobType.MATCH_APPLICATION, application.getId()).isEmpty());

        mvc.perform(get("/api/v1/interviews/{id}/scorecard/revisions", interview.getId()).with(user(MANAGER).roles("HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].revisedByUserId", is(interviewer.getId().intValue())))
                .andExpect(jsonPath("$.data[0].previous.recommendation", is("NO")))
                .andExpect(jsonPath("$.data[0].previous.ratings[0].notes", is("first")));
        mvc.perform(get("/api/v1/interviews/{id}/scorecard/revisions", interview.getId()).with(user(INTERVIEWER2).roles("INTERVIEWER")))
                .andExpect(status().isForbidden());

        // Alias route with the scorecard id in the path.
        mvc.perform(put("/api/v1/hr/interviews/{id}/scorecards/{scorecardId}", interview.getId(), scorecardId + 999)
                        .with(user(INTERVIEWER).roles("INTERVIEWER")).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isNotFound());
        // An edit that changes nothing keeps the history as it is.
        mvc.perform(editScorecard(interview.getId(), edit).with(user(INTERVIEWER).roles("INTERVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scorecard.revisionCount", is(1)));
        String secondEdit = "{\"ratings\":[{\"questionId\":\"q1\",\"rating\":3}],\"recommendation\":\"YES\"}";
        mvc.perform(put("/api/v1/hr/interviews/{id}/scorecards/{scorecardId}", interview.getId(), scorecardId)
                        .with(user(INTERVIEWER).roles("INTERVIEWER")).contentType(MediaType.APPLICATION_JSON).content(secondEdit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scorecard.recommendation", is("YES")))
                .andExpect(jsonPath("$.data.scorecard.summary").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.scorecard.revisionCount", is(2)));
        assertEquals(2, auditEvents.findAllByActionOrderByIdAsc(AuditActions.SCORECARD_UPDATED).size());

        Application decided = applications.findById(application.getId()).orElseThrow();
        decided.setStatus(ApplicationStatus.REJECTED);
        applications.saveAndFlush(decided);
        mvc.perform(editScorecard(interview.getId(), edit).with(user(INTERVIEWER).roles("INTERVIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInterviewWithoutAScorecardCannotBeEdited() throws Exception {
        Interview interview = scheduledInterview();
        mvc.perform(editScorecard(interview.getId(), "{\"ratings\":[],\"recommendation\":\"YES\"}")
                        .with(user(INTERVIEWER).roles("INTERVIEWER")))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private Interview scheduledInterview() {
        Application interviewing = screening.withStatus(applications.findById(application.getId()).orElseThrow(),
                ApplicationStatus.INTERVIEWING);
        Interview interview = new Interview();
        interview.setApplication(interviewing);
        interview.setInterviewerUserId(interviewer.getId());
        interview.setScheduledAt(UtcTime.now().minusHours(1));
        interview.setDurationMinutes(30);
        interview.setStatus(InterviewStatus.SCHEDULED);
        return interviews.save(interview);
    }

    private static MockHttpServletRequestBuilder editScorecard(Long interviewId, String body) {
        return put("/api/v1/interviews/{id}/scorecard", interviewId).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Notification latest(String email, NotificationTemplate template) {
        return notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(email, template).stream()
                .filter(row -> row.getChannel() == NotificationChannel.EMAIL)
                .max(Comparator.comparing(Notification::getId))
                .orElseThrow();
    }

    private String latestIcs(String email, NotificationTemplate template) {
        Notification row = latest(email, template);
        assertTrue(row.getAttachmentName() != null && row.getAttachmentName().endsWith(".ics"));
        return row.getAttachmentContent();
    }
}
