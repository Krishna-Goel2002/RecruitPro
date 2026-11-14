package com.recruitpro.fairness;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.fairness.entity.EeoResponse;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D14 / plan 5.5 and 5.7: the voluntary survey (consent, allowed values, delete, candidate-only) and the job and
 * organisation fairness reports (stage reached from the decision history, suppression, AIR flag, roles, tenancy).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FairnessIntegrationTest {

    private static final String SURVEY = "/api/v1/candidates/me/equal-opportunity";
    private static final String JOB_REPORT = "/api/v1/hr/jobs/{jobId}/fairness";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired EeoResponseRepository eeoResponses;
    @Autowired ApplicationDecisionHistoryRepository history;

    Job job;
    User managerUser;
    int counter;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization orgA = fixtures.organization("Fair Org A");
        Organization orgB = fixtures.organization("Fair Org B");
        HRProfile manager = fixtures.hrMember(orgA, "manager@fair-a.test", Role.HR_MANAGER);
        managerUser = manager.getUser();
        fixtures.staff(orgA, "admin@fair-a.test", Role.ADMIN);
        fixtures.staff(orgA, "recruiter@fair-a.test", Role.RECRUITER);
        fixtures.staff(orgA, "interviewer@fair-a.test", Role.INTERVIEWER);
        fixtures.hrMember(orgB, "manager@fair-b.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Analyst", JobStatus.PUBLISHED);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void theSurveyNeedsConsentAcceptsOnlyPlanValuesAndCanBeDeleted() throws Exception {
        fixtures.candidate("survey@fair-cand.test");
        var candidate = SecurityMockMvcRequestPostProcessors.user("survey@fair-cand.test").roles("CANDIDATE");
        mvc.perform(get(SURVEY).with(candidate)).andExpect(status().isNotFound());
        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"WOMAN\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"WOMAN\",\"consent\":false}")).andExpect(status().isBadRequest());
        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gender\":\"FEMALE\",\"consent\":true}")).andExpect(status().isBadRequest());
        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ageBand\":\"30_40\",\"consent\":true}")).andExpect(status().isBadRequest());
        assertEquals(0, eeoResponses.count());

        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"WOMAN\",\"ageBand\":\"25_34\",\"disability\":\"PREFER_NOT\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gender", is("WOMAN")))
                .andExpect(jsonPath("$.data.ageBand", is("25_34")))
                .andExpect(jsonPath("$.data.disability", is("PREFER_NOT")))
                .andExpect(jsonPath("$.data.socialCategory", nullValue()))
                .andExpect(jsonPath("$.data.consentGivenAt", notNullValue()));
        mvc.perform(put(SURVEY).with(candidate).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"MAN\",\"socialCategory\":\"OBC\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ageBand", nullValue()));
        assertEquals(1, eeoResponses.count(), "one row per candidate, replaced on save");
        mvc.perform(get(SURVEY).with(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gender", is("MAN")))
                .andExpect(jsonPath("$.data.socialCategory", is("OBC")));

        mvc.perform(get(SURVEY).with(staff("manager@fair-a.test", "HR_MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(get(SURVEY).with(staff("admin@fair-a.test", "ADMIN"))).andExpect(status().isForbidden());

        mvc.perform(delete(SURVEY).with(candidate)).andExpect(status().isOk());
        mvc.perform(get(SURVEY).with(candidate)).andExpect(status().isNotFound());
        mvc.perform(delete(SURVEY).with(candidate)).andExpect(status().isOk());
        assertEquals(0, eeoResponses.count());
    }

    @Test
    void reportsUseTheDecisionHistorySuppressSmallGroupsAndFlagAdverseImpact() throws Exception {
        // 6 women: one currently shortlisted, one rejected after being shortlisted, four never shortlisted.
        applicant("WOMAN", ApplicationStatus.SHORTLISTED, false);
        applicant("WOMAN", ApplicationStatus.REJECTED, true);
        for (int i = 0; i < 4; i++) {
            applicant("WOMAN", ApplicationStatus.SUBMITTED, false);
        }
        // 6 men: five shortlisted, one rejected without a shortlist.
        for (int i = 0; i < 5; i++) {
            applicant("MAN", ApplicationStatus.SHORTLISTED, false);
        }
        applicant("MAN", ApplicationStatus.REJECTED, false);
        // Two non-binary applicants (suppressed), one "prefer not" and one non-respondent (excluded).
        applicant("NON_BINARY", ApplicationStatus.SHORTLISTED, false);
        applicant("NON_BINARY", ApplicationStatus.SUBMITTED, false);
        applicant("PREFER_NOT", ApplicationStatus.SUBMITTED, false);
        applicant(null, ApplicationStatus.SHORTLISTED, false);

        String body = mvc.perform(get(JOB_REPORT, job.getId()).with(staff("manager@fair-a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope", is("JOB")))
                .andExpect(jsonPath("$.data.jobId", is(job.getId().intValue())))
                .andExpect(jsonPath("$.data.minimumGroupSize", is(5)))
                .andExpect(jsonPath("$.data.surveyResponseRate", is(0.9375)))
                .andExpect(jsonPath("$.data.note", notNullValue()))
                .andExpect(jsonPath("$.data.dimensions.length()", is(1)))
                .andExpect(jsonPath("$.data.dimensions[0].dimension", is("gender")))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].stage", is("SHORTLISTED")))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[0].group", is("WOMAN")))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[0].count", is(2)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[0].selectionRate", is(0.3333)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[1].group", is("MAN")))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[1].count", is(5)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[1].selectionRate", is(0.8333)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[2].group", is("NON_BINARY")))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[2].count", nullValue()))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[2].selectionRate", nullValue()))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].adverseImpactRatio", is(0.4)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].flagged", is(true)))
                .andExpect(jsonPath("$.data.dimensions[0].stages[0].flagged", is(false)))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("@fair-cand.test"), "reports are aggregates only");
        assertFalse(body.contains("PREFER_NOT"), "'prefer not to say' is never a group");

        mvc.perform(get(JOB_REPORT, job.getId()).with(staff("admin@fair-a.test", "ADMIN"))).andExpect(status().isOk());
        mvc.perform(get(JOB_REPORT, job.getId()).with(staff("recruiter@fair-a.test", "RECRUITER"))).andExpect(status().isForbidden());
        mvc.perform(get(JOB_REPORT, job.getId()).with(staff("interviewer@fair-a.test", "INTERVIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(JOB_REPORT, job.getId()).with(staff("manager@fair-b.test", "HR_MANAGER"))).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/admin/fairness").with(staff("admin@fair-a.test", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope", is("ORGANIZATION")))
                .andExpect(jsonPath("$.data.jobId", nullValue()))
                .andExpect(jsonPath("$.data.dimensions[0].stages[1].groups[0].count", is(2)));
        mvc.perform(get("/api/v1/admin/fairness").with(staff("manager@fair-a.test", "HR_MANAGER"))).andExpect(status().isForbidden());

        // Survey answers never reach the reviewer's applicant view.
        String applicants = mvc.perform(get("/api/v1/hr/jobs/{jobId}/applicants", job.getId())
                        .with(staff("manager@fair-a.test", "HR_MANAGER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(applicants.contains("WOMAN") || applicants.contains("NON_BINARY"));
        assertTrue(eeoResponses.count() > 0);
    }

    private void applicant(String gender, ApplicationStatus status, boolean shortlistedBefore) {
        counter++;
        String email = "applicant" + counter + "@fair-cand.test";
        CandidateProfile candidate = fixtures.candidate(email);
        Resume resume = screening.resume(candidate, "cv-" + counter + ".pdf");
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(3));
        if (shortlistedBefore) {
            event(application, ApplicationStatus.SUBMITTED, ApplicationStatus.SHORTLISTED, ApplicationDecisionAction.SHORTLIST);
            event(application, ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED, ApplicationDecisionAction.REJECT);
        }
        if (status != ApplicationStatus.SUBMITTED) {
            screening.withStatus(application, status);
        }
        if (gender != null) {
            EeoResponse response = new EeoResponse();
            response.setCandidateId(candidate.getId());
            response.setGender(gender);
            response.setConsentGivenAt(UtcTime.now());
            eeoResponses.save(response);
        }
    }

    private void event(Application application, ApplicationStatus from, ApplicationStatus to, ApplicationDecisionAction action) {
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(application);
        event.setPreviousStatus(from);
        event.setNewStatus(to);
        event.setActionType(action);
        event.setActor(managerUser);
        history.save(event);
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
