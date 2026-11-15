package com.recruitpro.verification;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.user.entity.Role;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.service.VerificationAuditActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff view (blind-first, SHADOW returns nothing), per-organisation reviews and the tier lift through
 * VerificationEvidenceContributor (plan v2 3.5 and section 5).
 */
class StaffVerificationIntegrationTest extends VerificationIntegrationTestBase {

    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired EvidenceItemRepository evidence;
    @Autowired AuditEventRepository audit;
    @Autowired BackgroundJobRepository jobs;
    @Autowired MatchingService matchingService;

    Organization visibleOrg;
    Organization otherOrg;
    Application application;
    Application otherApplication;
    ClaimVerdict shared;
    ClaimVerdict awaiting;

    @BeforeEach
    void setUp() {
        visibleOrg = fixtures.organization("Visible Org");
        HRProfile owner = fixtures.hrMember(visibleOrg, "hr@visible.test", Role.HR_MANAGER);
        fixtures.staff(visibleOrg, "recruiter@visible.test", Role.RECRUITER);
        fixtures.staff(visibleOrg, "interviewer@visible.test", Role.INTERVIEWER);
        otherOrg = fixtures.organization("Other Org");
        HRProfile otherOwner = fixtures.hrMember(otherOrg, "hr@other.test", Role.HR_MANAGER);
        Organization unrelated = fixtures.organization("Unrelated Org");
        fixtures.staff(unrelated, "hr@unrelated.test", Role.HR_MANAGER);

        CandidateProfile candidate = fixtures.candidate("staffview@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        ResumeAnalysisFact javaFact = screening.fact(analysis, ResumeFactType.SKILL, "Java");
        CandidateClaim react = verification.claim(candidate, ClaimType.SKILL, "React");

        Job job = fixtures.job(owner, "Frontend Engineer", JobStatus.PUBLISHED);
        screening.requirement(job, RequirementType.SKILL, "React", true, false);
        Job otherJob = fixtures.job(otherOwner, "UI Developer", JobStatus.PUBLISHED);
        screening.requirement(otherJob, RequirementType.SKILL, "React", true, false);
        application = screening.application(candidate, job, resume, UtcTime.now());
        otherApplication = screening.application(candidate, otherJob, resume, UtcTime.now());

        VerificationSource source = verification.source(candidate, VerificationSourceKind.GITHUB, "staff-view", true);
        EvidenceItem item = new EvidenceItem();
        item.setSourceId(source.getId());
        item.setCandidateId(candidate.getId());
        item.setItemKey("repo-1");
        item.setKind(EvidenceItemKind.REPOSITORY);
        item.setUrl("https://github.com/staff-view/dashboard");
        item.setRetrievedAt(UtcTime.now());
        item.setContentHash("sha256:" + "2".repeat(64));
        item = evidence.save(item);
        ClaimVerdict verdict = verification.verdict(source, react, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);
        verdict.setEvidenceItemIdsJson("[" + item.getId() + "]");
        verdict.setCandidateNote("My GitHub is staff-view");
        shared = verdicts.save(verdict);
        ClaimVerdict hidden = verification.verdict(source, react, VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.AWAITING_CANDIDATE);
        hidden.setSubjectType(SubjectType.RESUME_FACT);
        hidden.setSubjectId(javaFact.getId());
        awaiting = verdicts.save(hidden);
    }

    @Test
    void shadowModeShowsNothingAndRefusesReviews() throws Exception {
        mvc.perform(get("/api/v1/hr/applications/{id}/verification", application.getId()).with(staff("recruiter@visible.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode", is("SHADOW")))
                .andExpect(jsonPath("$.data.revealed", is(false)))
                .andExpect(jsonPath("$.data.claims", hasSize(0)));
        mvc.perform(post("/api/v1/hr/verdicts/{id}/review", shared.getId()).with(staff("hr@visible.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest());
        PreparedMatch prepared = matchingService.prepare(application.getId());
        assertNotNull(prepared);
        assertTrue(prepared.request().facts().stream().noneMatch(f -> f.id().startsWith("vf:")), "SHADOW: matching ignores verdicts");
    }

    @Test
    void visibleModeIsBlindFirstAndOnlyShowsSharedVerdicts() throws Exception {
        verification.mode(visibleOrg.getId(), VerificationMode.VISIBLE);
        String path = "/api/v1/hr/applications/{id}/verification";
        mvc.perform(get(path, application.getId()).with(staff("recruiter@visible.test", "RECRUITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode", is("VISIBLE")))
                .andExpect(jsonPath("$.data.revealed", is(false)))
                .andExpect(jsonPath("$.data.claims", hasSize(1)))
                .andExpect(jsonPath("$.data.claims[0].subjectType", is("CANDIDATE_CLAIM")))
                .andExpect(jsonPath("$.data.claims[0].value", is("React")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts", hasSize(1)))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].id", is(shared.getId().intValue())))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].verdict", is("VERIFIED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].reasons[0].text", is("Repository A uses it.")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].evidence", nullValue()))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].evidenceCount", is(1)))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].candidateNote", nullValue()))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].myOrgReview", nullValue()));
        // Other organisations' staff and interviewers cannot read it.
        mvc.perform(get(path, application.getId()).with(staff("hr@other.test", "HR_MANAGER"))).andExpect(status().isNotFound());
        mvc.perform(get(path, application.getId()).with(staff("interviewer@visible.test", "INTERVIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(path, application.getId()).with(candidate("staffview@cand.test"))).andExpect(status().isForbidden());

        Application revealed = applications.findById(application.getId()).orElseThrow();
        revealed.setIdentityRevealedAt(UtcTime.now());
        applications.save(revealed);
        mvc.perform(get(path, application.getId()).with(staff("hr@visible.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.revealed", is(true)))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].evidence[0].url", is("https://github.com/staff-view/dashboard")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].candidateNote", is("My GitHub is staff-view")));
    }

    @Test
    void reviewsArePerOrganisationAndLiftTheTierOnlyThere() throws Exception {
        verification.mode(visibleOrg.getId(), VerificationMode.VISIBLE);
        verification.mode(otherOrg.getId(), VerificationMode.VISIBLE);
        String review = "/api/v1/hr/verdicts/{id}/review";
        mvc.perform(post(review, shared.getId()).with(staff("interviewer@visible.test", "INTERVIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")).andExpect(status().isForbidden());
        mvc.perform(post(review, shared.getId()).with(staff("hr@unrelated.test", "HR_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")).andExpect(status().isNotFound());
        mvc.perform(post(review, awaiting.getId()).with(staff("recruiter@visible.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")).andExpect(status().isNotFound());
        mvc.perform(post(review, shared.getId()).with(staff("recruiter@visible.test", "RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"OVERRULE\"}")).andExpect(status().isBadRequest());

        mvc.perform(post(review, shared.getId()).with(staff("recruiter@visible.test", "RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\",\"note\":\"Checked the repositories\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myOrgReview.decision", is("CONFIRM")))
                .andExpect(jsonPath("$.data.myOrgReview.reviewerName", is("Test User")))
                .andExpect(jsonPath("$.data.myOrgReview.note", is("Checked the repositories")));
        assertEquals(1, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.VERDICT_REVIEWED).size());
        assertTrue(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, application.getId()).stream()
                .anyMatch(job -> job.getStatus() == BackgroundJobStatus.PENDING), "the confirming organisation re-matches");
        assertTrue(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, otherApplication.getId()).isEmpty(),
                "other organisations are not re-matched");

        mvc.perform(get("/api/v1/hr/applications/{id}/verification", application.getId()).with(staff("hr@visible.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("CORROBORATED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].myOrgReview.decision", is("CONFIRM")));
        mvc.perform(get("/api/v1/hr/applications/{id}/verification", otherApplication.getId()).with(staff("hr@other.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].myOrgReview", nullValue()));

        AiMatchRequest.Fact lifted = vf(matchingService.prepare(application.getId()));
        assertNotNull(lifted, "the confirming organisation's match request carries the verified fact");
        assertEquals("vf:" + shared.getId(), lifted.id());
        assertEquals("CORROBORATED", lifted.tier());
        assertEquals("SKILL", lifted.type());
        assertEquals("React", lifted.value());
        assertEquals("verification", lifted.sourceSection());
        assertEquals("Verified by code; confirmed by a recruiter", lifted.evidence());
        assertTrue(lifted.id().length() <= 40);
        assertEquals(null, vf(matchingService.prepare(otherApplication.getId())), "no lift for another organisation");

        // A later OVERRULE wins; switching back to SHADOW also removes the lift.
        mvc.perform(post(review, shared.getId()).with(staff("hr@visible.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"OVERRULE\",\"note\":\"Forked code\"}"))
                .andExpect(status().isOk());
        assertEquals(null, vf(matchingService.prepare(application.getId())));
        mvc.perform(post(review, shared.getId()).with(staff("hr@visible.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isOk());
        assertNotNull(vf(matchingService.prepare(application.getId())));
        verification.mode(visibleOrg.getId(), VerificationMode.SHADOW);
        assertEquals(null, vf(matchingService.prepare(application.getId())));
        assertFalse(verdicts.findById(awaiting.getId()).orElseThrow().getCandidateStatus() == CandidateVerdictStatus.SHARED);
    }

    private static AiMatchRequest.Fact vf(PreparedMatch prepared) {
        assertNotNull(prepared);
        List<AiMatchRequest.Fact> facts = prepared.request().facts().stream().filter(f -> f.id().startsWith("vf:")).toList();
        assertTrue(facts.size() <= 1);
        return facts.isEmpty() ? null : facts.get(0);
    }
}
