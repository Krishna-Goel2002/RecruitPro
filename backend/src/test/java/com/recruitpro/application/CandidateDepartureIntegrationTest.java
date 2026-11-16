package com.recruitpro.application;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
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
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R2-M01: a withdrawal ends what is still open for the application (scheduled interviews cancelled with an ICS
 * cancellation to the interviewer and the candidate, unfinished work samples cancelled with a reason); R2-M05: an
 * interview of an application that is no longer active can be cancelled but not rescheduled; R2-L03: an anonymised
 * (deleted) candidate's application can no longer be decided on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateDepartureIntegrationTest {

    private static final String CANDIDATE = "leaving@cand.test";
    private static final String MANAGER = "manager@depart.test";
    private static final String INTERVIEWER = "interviewer@depart.test";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired InterviewRepository interviews;
    @Autowired WorkSampleRepository workSamples;
    @Autowired WorkSampleAssignmentRepository assignments;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;

    Job job;
    User interviewer;
    Application application;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Departure Org");
        HRProfile manager = fixtures.hrMember(organization, MANAGER, Role.HR_MANAGER);
        interviewer = fixtures.staff(organization, INTERVIEWER, Role.INTERVIEWER);
        job = fixtures.job(manager, "Payroll Analyst", JobStatus.PUBLISHED);
        CandidateProfile candidate = fixtures.candidate(CANDIDATE);
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application created = screening.application(candidate, job, resume, UtcTime.now().minusDays(2));
        created.setIdentityRevealedAt(UtcTime.now().minusDays(1));
        application = screening.withStatus(created, ApplicationStatus.INTERVIEWING);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void withdrawingCancelsScheduledInterviewsAndUnfinishedWorkSamples() throws Exception {
        Interview scheduled = interview(InterviewStatus.SCHEDULED);
        Interview completed = interview(InterviewStatus.COMPLETED);
        WorkSample sample = sample();
        WorkSampleAssignment started = assignment(sample, WorkSampleAssignmentStatus.STARTED);
        WorkSample other = sample();
        WorkSampleAssignment scored = assignment(other, WorkSampleAssignmentStatus.SCORED);

        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", application.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(CANDIDATE).roles("CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));

        Interview cancelled = interviews.findById(scheduled.getId()).orElseThrow();
        assertEquals(InterviewStatus.CANCELLED, cancelled.getStatus());
        assertEquals(1, cancelled.getIcsSequence(), "a higher SEQUENCE lets calendars drop the event");
        assertEquals(InterviewStatus.COMPLETED, interviews.findById(completed.getId()).orElseThrow().getStatus());

        Notification toInterviewer = latestEmail(INTERVIEWER, NotificationTemplate.INTERVIEW_CANCELLED);
        assertEquals("cancel.ics", toInterviewer.getAttachmentName());
        assertTrue(toInterviewer.getAttachmentContent().contains("METHOD:CANCEL"));
        Notification toCandidate = latestEmail(CANDIDATE, NotificationTemplate.INTERVIEW_CANCELLED);
        assertTrue(toCandidate.getAttachmentContent().contains("METHOD:CANCEL"));

        WorkSampleAssignment closed = assignments.findById(started.getId()).orElseThrow();
        assertEquals(WorkSampleAssignmentStatus.CANCELLED, closed.getStatus());
        assertNotNull(closed.getCancelledAt());
        assertEquals("The candidate withdrew the application", closed.getCancelReason());
        assertEquals(WorkSampleAssignmentStatus.SCORED, assignments.findById(scored.getId()).orElseThrow().getStatus(),
                "finished work stays as it is");
    }

    @Test
    void anInterviewOfAnInactiveApplicationCanBeCancelledButNotRescheduled() throws Exception {
        Interview scheduled = interview(InterviewStatus.SCHEDULED);
        screening.withStatus(applications.findById(application.getId()).orElseThrow(), ApplicationStatus.REJECTED);
        String later = UtcTime.format(UtcTime.now().plusDays(5).withNano(0));

        mvc.perform(patch("/api/v1/interviews/{id}", scheduled.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"" + later + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, interviews.findById(scheduled.getId()).orElseThrow().getIcsSequence());
        assertTrue(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(CANDIDATE,
                NotificationTemplate.INTERVIEW_SCHEDULED).isEmpty(), "no new invitation for a rejected candidate");

        mvc.perform(patch("/api/v1/interviews/{id}", scheduled.getId()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void aDeletedCandidatesApplicationCanNoLongerBeDecidedOn() throws Exception {
        Application rejected = screening.withStatus(applications.findById(application.getId()).orElseThrow(),
                ApplicationStatus.REJECTED);
        User candidateUser = users.findByEmail(CANDIDATE).orElseThrow();
        candidateUser.setDeletedAt(UtcTime.now());
        candidateUser.setEnabled(false);
        users.save(candidateUser);

        mvc.perform(post("/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/decisions", job.getId(), rejected.getId())
                        .with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REOPEN\",\"comment\":\"Reconsider\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(ApplicationStatus.REJECTED, applications.findById(rejected.getId()).orElseThrow().getStatus());
    }

    // ---- helpers ----

    private Interview interview(InterviewStatus status) {
        Interview interview = new Interview();
        interview.setApplication(application);
        interview.setInterviewerUserId(interviewer.getId());
        interview.setScheduledAt(UtcTime.now().plusDays(2));
        interview.setDurationMinutes(45);
        interview.setStatus(status);
        return interviews.save(interview);
    }

    private WorkSample sample() {
        WorkSample sample = new WorkSample();
        sample.setJob(job);
        sample.setTitle("Payroll task");
        sample.setInstructions("Compute the payroll.");
        sample.setTimeLimitMinutes(30);
        sample.setRubricJson("[]");
        sample.setStatus(WorkSampleStatus.ACTIVE);
        return workSamples.save(sample);
    }

    private WorkSampleAssignment assignment(WorkSample sample, WorkSampleAssignmentStatus status) {
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(sample);
        assignment.setApplication(application);
        assignment.setStatus(status);
        assignment.setAssignedAt(UtcTime.now().minusHours(2));
        assignment.setStartedAt(UtcTime.now().minusMinutes(10));
        return assignments.save(assignment);
    }

    private Notification latestEmail(String email, NotificationTemplate template) {
        List<Notification> rows = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(email, template).stream()
                .filter(row -> row.getChannel() == NotificationChannel.EMAIL).toList();
        assertFalse(rows.isEmpty(), "an e-mail " + template + " to " + email);
        return rows.get(rows.size() - 1);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor manager() {
        return SecurityMockMvcRequestPostProcessors.user(MANAGER).roles("HR_MANAGER");
    }
}
