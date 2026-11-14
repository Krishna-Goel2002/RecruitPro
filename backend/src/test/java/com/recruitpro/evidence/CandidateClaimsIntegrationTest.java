package com.recruitpro.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
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
 * C1 / D5: candidate claims are SELF_CLAIMED, a fact correction becomes a linked claim and never overwrites the
 * extracted value, and every claim change re-queues matching of the candidate's applications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateClaimsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired CandidateClaimRepository claims;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired BackgroundJobRepository backgroundJobs;

    CandidateProfile candidate;
    Resume resume;
    ResumeAnalysis analysis;
    ResumeAnalysisFact javaFact;
    ResumeAnalysisFact emailFact;
    Application application;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        candidate = fixtures.candidate("claimant@cand.test");
        fixtures.candidate("someone-else@cand.test");
        resume = screening.resume(candidate, "cv.pdf");
        analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        analysis.setFlagsJson("[{\"code\":\"HIDDEN_TEXT\",\"detail\":\"12 characters of hidden text were excluded.\"}]");
        analysis = analyses.save(analysis);
        javaFact = screening.fact(analysis, ResumeFactType.SKILL, "Java");
        emailFact = screening.fact(analysis, ResumeFactType.EMAIL, "claimant@cand.test");
        var owner = fixtures.hrMember(fixtures.organization("Claims Org"), "owner@claims.test", Role.HR_MANAGER);
        Job job = fixtures.job(owner, "Backend Engineer", JobStatus.PUBLISHED);
        application = screening.application(candidate, job, resume, UtcTime.now());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void candidatesAddListAndDeleteSelfClaimedClaims() throws Exception {
        var me = candidateAuth("claimant@cand.test");
        mvc.perform(get("/api/v1/candidates/me/claims").with(me)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()", is(0)));
        String created = mvc.perform(post("/api/v1/candidates/me/claims").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claimType\":\"CERTIFICATION\",\"value\":\" AWS Solutions Architect \",\"evidenceUrl\":\"https://credly.example.test/badge/1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value", is("AWS Solutions Architect")))
                .andExpect(jsonPath("$.data.tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.verificationStatus", is("UNVERIFIED")))
                .andExpect(jsonPath("$.data.evidenceUrl", is("https://credly.example.test/badge/1")))
                .andReturn().getResponse().getContentAsString();
        long claimId = mapper.readTree(created).path("data").path("id").asLong();
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, application.getId())
                .stream().anyMatch(job -> job.getStatus() == BackgroundJobStatus.PENDING), "a new claim re-queues matching");

        mvc.perform(post("/api/v1/candidates/me/claims").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"No type\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/candidates/me/claims").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"SKILL\",\"value\":\"Go\",\"evidenceUrl\":\"javascript:alert(1)\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/candidates/me/claims").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimType\":\"SKILL\",\"value\":\"   \"}")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/candidates/me/claims").with(me)).andExpect(jsonPath("$.data.length()", is(1)));
        mvc.perform(get("/api/v1/candidates/me/claims").with(candidateAuth("someone-else@cand.test")))
                .andExpect(jsonPath("$.data.length()", is(0)));
        mvc.perform(delete("/api/v1/candidates/me/claims/{id}", claimId).with(candidateAuth("someone-else@cand.test")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidates/me/claims").with(SecurityMockMvcRequestPostProcessors.user("owner@claims.test").roles("HR_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/candidates/me/claims/{id}", claimId).with(me)).andExpect(status().isOk());
        assertTrue(claims.findById(claimId).isEmpty());
    }

    @Test
    void aFactCorrectionBecomesALinkedSelfClaimedClaimAndNeverOverwritesTheExtractedValue() throws Exception {
        var me = candidateAuth("claimant@cand.test");
        String path = "/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}";
        mvc.perform(patch(path, resume.getId(), javaFact.getId()).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Java 21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value", is("Java 21")))
                .andExpect(jsonPath("$.data.extractedValue", is("Java")))
                .andExpect(jsonPath("$.data.claimedValue", is("Java 21")))
                .andExpect(jsonPath("$.data.tier", is("RESUME_STATED")))
                .andExpect(jsonPath("$.data.reviewStatus", is("CORRECTED")))
                .andExpect(jsonPath("$.data.claimId", notNullValue()));
        ResumeAnalysisFact stored = facts.findById(javaFact.getId()).orElseThrow();
        assertEquals("Java", stored.getExtractedValue());
        assertEquals(EvidenceTier.RESUME_STATED, stored.getEvidenceTier());
        List<CandidateClaim> linked = claims.findAllByCandidateIdAndSourceFactId(candidate.getId(), javaFact.getId());
        assertEquals(1, linked.size());
        assertEquals("Java 21", linked.get(0).getClaimValue());
        assertEquals(ClaimType.SKILL, linked.get(0).getClaimType());
        assertEquals(EvidenceTier.SELF_CLAIMED, linked.get(0).getTier());

        // A recruiter had verified it; changing the value again resets the verification.
        CandidateClaim verified = linked.get(0);
        verified.setTier(EvidenceTier.CORROBORATED);
        verified.setVerifiedAt(UtcTime.now());
        claims.save(verified);
        mvc.perform(patch(path, resume.getId(), javaFact.getId()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"Java 21 and Kotlin\"}")).andExpect(status().isOk());
        linked = claims.findAllByCandidateIdAndSourceFactId(candidate.getId(), javaFact.getId());
        assertEquals(1, linked.size(), "the linked claim is updated, not duplicated");
        assertEquals("Java 21 and Kotlin", linked.get(0).getClaimValue());
        assertEquals(EvidenceTier.SELF_CLAIMED, linked.get(0).getTier());
        assertNull(linked.get(0).getVerifiedAt());

        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/analysis", resume.getId()).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags[0].code", is("HIDDEN_TEXT")))
                .andExpect(jsonPath("$.data.facts[0].claimId", is(linked.get(0).getId().intValue())));

        mvc.perform(patch(path, resume.getId(), javaFact.getId()).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.claimedValue").doesNotExist());
        assertTrue(claims.findAllByCandidateIdAndSourceFactId(candidate.getId(), javaFact.getId()).isEmpty(),
                "confirming the extracted value withdraws the correction");

        mvc.perform(patch(path, resume.getId(), javaFact.getId()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"Java 17\"}")).andExpect(status().isOk());
        CandidateClaim again = claims.findAllByCandidateIdAndSourceFactId(candidate.getId(), javaFact.getId()).get(0);
        mvc.perform(delete("/api/v1/candidates/me/claims/{id}", again.getId()).with(me)).andExpect(status().isOk());
        ResumeAnalysisFact reset = facts.findById(javaFact.getId()).orElseThrow();
        assertEquals(ResumeFactReviewStatus.UNREVIEWED, reset.getReviewStatus());
        assertNull(reset.getReviewedValue());
        assertEquals("Java", reset.getExtractedValue());
    }

    @Test
    void contactFactCorrectionsNeverBecomeClaims() throws Exception {
        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}", resume.getId(), emailFact.getId())
                        .with(candidateAuth("claimant@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"new-address@cand.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus", is("CORRECTED")))
                .andExpect(jsonPath("$.data.claimId").doesNotExist());
        assertEquals(0, claims.countByCandidateId(candidate.getId()));
        mvc.perform(patch("/api/v1/candidates/me/resumes/{resumeId}/analysis/facts/{factId}", resume.getId(), javaFact.getId())
                        .with(candidateAuth("someone-else@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Other\"}"))
                .andExpect(status().isNotFound());
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidateAuth(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }
}
