package com.recruitpro.evidence;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.repository.CandidateClaimVerificationRepository;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R2-H02: a claim verification belongs to the verifying organisation. A second organisation's verification (here a
 * rejection) is stored separately and never overwrites the first one: organisation A keeps the claim as CORROBORATED
 * evidence while organisation B no longer uses it. (Changing a correction claim's value clears every organisation's
 * row: {@code CandidateClaimService.upsertForFact}.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClaimVerificationPerOrganizationIntegrationTest {

    private static final String DECISIONS = "/api/v1/hr/jobs/{jobId}/applicants/{applicationId}";

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired CandidateClaimRepository claims;
    @Autowired CandidateClaimVerificationRepository verifications;
    @Autowired MatchingService matchingService;

    Organization orgA;
    Organization orgB;
    Job jobA;
    Job jobB;
    Application atA;
    Application atB;
    CandidateClaim claim;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        orgA = fixtures.organization("Claims Org A");
        orgB = fixtures.organization("Claims Org B");
        HRProfile managerA = fixtures.hrMember(orgA, "manager@claims-a.test", Role.HR_MANAGER);
        HRProfile managerB = fixtures.hrMember(orgB, "manager@claims-b.test", Role.HR_MANAGER);
        fixtures.staff(orgB, "recruiter@claims-b.test", Role.RECRUITER);
        jobA = fixtures.job(managerA, "Cloud Engineer A", JobStatus.PUBLISHED);
        jobB = fixtures.job(managerB, "Cloud Engineer B", JobStatus.PUBLISHED);
        screening.requirement(jobA, RequirementType.CERTIFICATION, "AWS Solutions Architect", true, true);
        screening.requirement(jobB, RequirementType.CERTIFICATION, "AWS Solutions Architect", true, true);
        CandidateProfile candidate = fixtures.candidate("claims@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        atA = screening.application(candidate, jobA, resume, UtcTime.now().minusDays(2));
        atB = screening.application(candidate, jobB, resume, UtcTime.now().minusDays(1));
        CandidateClaim created = new CandidateClaim();
        created.setCandidate(candidate);
        created.setClaimType(ClaimType.CERTIFICATION);
        created.setClaimValue("AWS Solutions Architect");
        claim = claims.save(created);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void aSecondOrganisationsVerdictNeverOverwritesTheFirst() throws Exception {
        verify("manager@claims-a.test", "HR_MANAGER", jobA, atA, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier", is("CORROBORATED")))
                .andExpect(jsonPath("$.data.verificationStatus", is("VERIFIED")));
        verify("recruiter@claims-b.test", "RECRUITER", jobB, atB, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.verificationStatus", is("REJECTED")));

        // Organisation A still sees (and matches on) its own corroboration.
        mvc.perform(get(DECISIONS, jobA.getId(), atA.getId()).with(staff("manager@claims-a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verificationStatus", contains("VERIFIED")))
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].tier", contains("CORROBORATED")));
        mvc.perform(get(DECISIONS, jobB.getId(), atB.getId()).with(staff("manager@claims-b.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verificationStatus", contains("REJECTED")));

        Optional<AiMatchRequest.Fact> forA = claimFact(matchingService.prepare(atA.getId()));
        assertTrue(forA.isPresent());
        assertEquals("CORROBORATED", forA.get().tier(), "organisation B's rejection does not remove A's corroboration");
        assertTrue(claimFact(matchingService.prepare(atB.getId())).isEmpty(), "B rejected it, so B does not match on it");
        assertEquals(2, verifications.count());
        // Integration pass: the candidate sees the summary of every organisation's row (a corroboration wins), not
        // whichever organisation happened to check last.
        mvc.perform(get("/api/v1/candidates/me/claims")
                        .with(SecurityMockMvcRequestPostProcessors.user("claims@cand.test").roles("CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verificationStatus", is("VERIFIED")))
                .andExpect(jsonPath("$.data[0].tier", is("CORROBORATED")));
    }

    @Test
    void verificationsAreKeptPerOrganisationAndTheCandidateSeesTheSummary() throws Exception {
        verify("manager@claims-a.test", "HR_MANAGER", jobA, atA, false).andExpect(status().isOk());
        verify("manager@claims-a.test", "HR_MANAGER", jobA, atA, true).andExpect(status().isOk());
        assertEquals(1, verifications.count(), "one row per claim and organisation, updated in place");
        assertEquals(EvidenceTier.CORROBORATED,
                verifications.findByClaimIdAndOrganizationId(claim.getId(), orgA.getId()).orElseThrow().getTier());
        // Organisation B never checked it: unverified there, sent as a plain self-claim.
        mvc.perform(get(DECISIONS, jobB.getId(), atB.getId()).with(staff("manager@claims-b.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verificationStatus", contains("UNVERIFIED")))
                .andExpect(jsonPath("$.data.claims[?(@.id == " + claim.getId() + ")].verifiedAt").isEmpty());
        assertEquals("SELF_CLAIMED", claimFact(matchingService.prepare(atB.getId())).orElseThrow().tier());
        mvc.perform(get("/api/v1/candidates/me/claims")
                        .with(SecurityMockMvcRequestPostProcessors.user("claims@cand.test").roles("CANDIDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verificationStatus", is("VERIFIED")));
    }

    private org.springframework.test.web.servlet.ResultActions verify(String email, String role, Job job, Application application,
                                                                      boolean verified) throws Exception {
        return mvc.perform(post(DECISIONS + "/claims/{claimId}/verification", job.getId(), application.getId(), claim.getId())
                .with(staff(email, role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verified\":" + verified + "}"));
    }

    private Optional<AiMatchRequest.Fact> claimFact(PreparedMatch prepared) {
        assertNotNull(prepared, "there is evidence to match");
        return prepared.request().facts().stream().filter(fact -> ("claim:" + claim.getId()).equals(fact.id())).findFirst();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor staff(String email, String role) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles(role);
    }
}
