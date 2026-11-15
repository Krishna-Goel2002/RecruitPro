package com.recruitpro.fairness;

import com.recruitpro.application.entity.Application;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fairness reports filtered by application creation date (from/to inclusive, ISO dates; WP-PLAT). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FairnessDateFilterIntegrationTest {

    private static final String JOB_REPORT = "/api/v1/hr/jobs/{jobId}/fairness";
    private static final String APPLIED_WOMEN = "$.data.dimensions[0].stages[0].groups[0].count";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired EeoResponseRepository eeoResponses;
    @Autowired JdbcTemplate jdbcTemplate;

    Job job;
    int counter;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Fair Dates Org");
        HRProfile manager = fixtures.hrMember(organization, "manager@fair-dates.test", Role.HR_MANAGER);
        fixtures.staff(organization, "admin@fair-dates.test", Role.ADMIN);
        job = fixtures.job(manager, "Analyst", JobStatus.PUBLISHED);
        for (int i = 0; i < 6; i++) {
            applicant(LocalDateTime.of(2026, 1, 10, 12, 0));
        }
        for (int i = 0; i < 5; i++) {
            applicant(LocalDateTime.of(2026, 3, 1, 23, 59));
        }
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void reportsCountOnlyApplicationsCreatedInTheRange() throws Exception {
        var manager = user("manager@fair-dates.test").roles("HR_MANAGER");
        mvc.perform(get(JOB_REPORT, job.getId()).with(manager)).andExpect(status().isOk())
                .andExpect(jsonPath(APPLIED_WOMEN, is(11)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "2026-01-01").param("to", "2026-01-31").with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath(APPLIED_WOMEN, is(6)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "2026-03-01").param("to", "2026-03-01").with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath(APPLIED_WOMEN, is(5)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "2026-02-01").with(manager))
                .andExpect(jsonPath(APPLIED_WOMEN, is(5)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("to", "2026-02-28").with(manager))
                .andExpect(jsonPath(APPLIED_WOMEN, is(6)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "2026-04-01").with(manager))
                .andExpect(jsonPath("$.data.dimensions.length()", is(0)));
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "2026-03-02").param("to", "2026-03-01").with(manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get(JOB_REPORT, job.getId()).param("from", "01/03/2026").with(manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/fairness").param("from", "2026-03-01").param("to", "2026-03-01")
                        .with(user("admin@fair-dates.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(APPLIED_WOMEN, is(5)));
    }

    private void applicant(LocalDateTime createdAt) {
        counter++;
        CandidateProfile candidate = fixtures.candidate("dates" + counter + "@fair-cand.test");
        Resume resume = screening.resume(candidate, "cv-" + counter + ".pdf");
        Application application = screening.application(candidate, job, resume, UtcTime.now().minusDays(3));
        jdbcTemplate.update("UPDATE applications SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt),
                application.getId());
        EeoResponse response = new EeoResponse();
        response.setCandidateId(candidate.getId());
        response.setGender("WOMAN");
        response.setConsentGivenAt(UtcTime.now());
        eeoResponses.save(response);
    }
}
