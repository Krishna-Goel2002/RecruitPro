package com.recruitpro.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.common.time.UtcTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R2-M02 (candidate single-resume deletion, upload cap), R2-L02 (control characters in file names) and R2-L06
 * (no-store on the candidate's own download).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeDeletionIntegrationTest {

    private static final String EMAIL = "resume-delete@cand.test";
    private static final byte[] PDF = "%PDF-1.7\nresume deletion test\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ResumeRepository resumes;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired CandidateClaimRepository claims;
    @Autowired ResumeStorage resumeStorage;

    CandidateProfile candidate;
    Job job;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Resume Delete Org");
        HRProfile manager = fixtures.hrMember(organization, "manager@resume-delete.test", Role.HR_MANAGER);
        job = fixtures.job(manager, "Accountant", JobStatus.PUBLISHED);
        candidate = fixtures.candidate(EMAIL);
        fixtures.candidate("other@resume-delete.test");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void anUnusedResumeIsDeletedWithItsAnalysisCorrectionClaimsAndFile() throws Exception {
        Resume resume = screening.resume(candidate, "cv.pdf");
        resumeStorage.store(resume.getStorageKey(), PDF);
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        ResumeAnalysisFact fact = screening.fact(analysis, ResumeFactType.SKILL, "Tally");
        CandidateClaim correction = new CandidateClaim();
        correction.setCandidate(candidate);
        correction.setClaimType(ClaimType.SKILL);
        correction.setClaimValue("Tally ERP 9");
        correction.setSourceFactId(fact.getId());
        correction = claims.save(correction);
        CandidateClaim freeStanding = new CandidateClaim();
        freeStanding.setCandidate(candidate);
        freeStanding.setClaimType(ClaimType.SKILL);
        freeStanding.setClaimValue("GST filing");
        freeStanding = claims.save(freeStanding);

        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/download", resume.getId()).with(candidate()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete("/api/v1/candidates/me/resumes/{id}", resume.getId()).with(other()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/candidates/me/resumes/{id}", resume.getId()).with(candidate()))
                .andExpect(status().isOk());

        assertTrue(resumes.findById(resume.getId()).isEmpty());
        assertTrue(analyses.findById(analysis.getId()).isEmpty());
        assertTrue(facts.findById(fact.getId()).isEmpty());
        assertTrue(claims.findById(correction.getId()).isEmpty(), "the correction of a deleted fact goes with it");
        assertTrue(claims.findById(freeStanding.getId()).isPresent(), "free-standing claims are not touched");
        assertThrows(IOException.class, () -> resumeStorage.load(resume.getStorageKey()), "the file is deleted");
        mvc.perform(delete("/api/v1/candidates/me/resumes/{id}", resume.getId()).with(candidate()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aResumeUsedForAnApplicationIsKept() throws Exception {
        Resume resume = screening.resume(candidate, "used.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        screening.application(candidate, job, resume, UtcTime.now().minusDays(1));

        mvc.perform(delete("/api/v1/candidates/me/resumes/{id}", resume.getId()).with(candidate()))
                .andExpect(status().isConflict());
        assertTrue(resumes.findById(resume.getId()).isPresent());
    }

    @Test
    void uploadsAreCappedAndFileNamesWithControlCharactersAreRefused() throws Exception {
        mvc.perform(multipart("/api/v1/candidates/me/resumes")
                        .file(new MockMultipartFile("file", "cv\u0000.pdf", "application/pdf", PDF)).with(candidate()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/candidates/me/resumes")
                        .file(new MockMultipartFile("file", "cv\t.pdf", "application/pdf", PDF)).with(candidate()))
                .andExpect(status().isBadRequest());

        for (int i = 0; i < 20; i++) {
            screening.resume(candidate, "old-" + i + ".pdf");
        }
        mvc.perform(multipart("/api/v1/candidates/me/resumes")
                        .file(new MockMultipartFile("file", "one-too-many.pdf", "application/pdf", PDF)).with(candidate()))
                .andExpect(status().isBadRequest());
        assertEquals(20, resumes.countByCandidateId(candidate.getId()));
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidate() {
        return SecurityMockMvcRequestPostProcessors.user(EMAIL).roles("CANDIDATE");
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor other() {
        return SecurityMockMvcRequestPostProcessors.user("other@resume-delete.test").roles("CANDIDATE");
    }
}
