package com.recruitpro.application;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S4: with app.auth.require-verified-email-to-apply=true (prod) unverified candidates cannot apply. */
@SpringBootTest(properties = "app.auth.require-verified-email-to-apply=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplyRequiresVerifiedEmailIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired UserRepository users;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void onlyVerifiedCandidatesMayApply() throws Exception {
        HRProfile owner = fixtures.hrMember(fixtures.organization("Verified Org"), "owner@verified.test", Role.HR_MANAGER);
        Job job = fixtures.job(owner, "Analyst", JobStatus.PUBLISHED);
        CandidateProfile candidate = fixtures.candidate("unverified@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        var auth = SecurityMockMvcRequestPostProcessors.user("unverified@cand.test").roles("CANDIDATE");
        String body = "{\"resumeId\":" + resume.getId() + "}";

        mvc.perform(post("/api/v1/jobs/{jobId}/applications", job.getId()).with(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        User user = users.findByEmail("unverified@cand.test").orElseThrow();
        user.setEmailVerifiedAt(UtcTime.now());
        users.save(user);
        mvc.perform(post("/api/v1/jobs/{jobId}/applications", job.getId()).with(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
