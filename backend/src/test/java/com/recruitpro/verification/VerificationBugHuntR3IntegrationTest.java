package com.recruitpro.verification;

import com.recruitpro.application.entity.Application;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.CandidateClaimVerification;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.repository.CandidateClaimVerificationRepository;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.user.entity.Role;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.dto.VerificationExport;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.ReferenceRelationship;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationCandidateLockRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.GitHubOAuthBindingCookie;
import com.recruitpro.verification.service.GitHubOAuthService;
import com.recruitpro.verification.service.ReferenceService;
import com.recruitpro.verification.service.VerificationPrivacyService;
import com.recruitpro.verification.service.VerificationResultWriter;
import com.recruitpro.verification.service.VerificationRunService;
import com.recruitpro.verification.service.VerificationSourceService;
import com.recruitpro.verification.service.VerifySourceJobHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug hunt R3 (claim verification, 16 Nov 2026): regression tests for the backend findings R3-B01 ... R3-B21 of
 * docs/audit/BUG_HUNT_R3_VERIFICATION.md. Each test fails on the code before the fix.
 */
class VerificationBugHuntR3IntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/candidates/me/verification";
    private static final String PUBLIC_REFERENCE = "/api/v1/public/references/{token}";
    private static final Pattern REFERENCE_LINK = Pattern.compile("http://localhost:5173/reference/([A-Za-z0-9_-]{43})");

    @Autowired ScreeningFixtures screening;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired VerificationCandidateLockRepository lockRepository;
    @Autowired VerificationSourceRepository sources;
    @Autowired VerificationReferenceRequestRepository references;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired ClaimVerdictReviewRepository reviews;
    @Autowired EvidenceItemRepository evidence;
    @Autowired CandidateClaimRepository claims;
    @Autowired CandidateClaimVerificationRepository claimVerifications;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobRepository jobs;
    @Autowired VerificationRunService runService;
    @Autowired VerificationResultWriter resultWriter;
    @Autowired VerifySourceJobHandler handler;
    @Autowired VerificationPrivacyService privacyService;
    @Autowired com.recruitpro.evidence.service.MatchingService matchingService;

    // ------------------------------------------------------------------------------------------- R3-B01

    @Test
    void aResultStoredWhileTheSourceIsBeingRevokedIsNotKept() throws Exception {
        CandidateProfile me = fixtures.candidate("race@cand.test");
        CandidateClaim go = verification.claim(me, ClaimType.SKILL, "Go");
        VerificationSource github = verification.source(me, VerificationSourceKind.GITHUB, "racer", true);
        long runId = startRun("race@cand.test", github.getId());
        assertNotNull(runService.claim(runId));
        AgentModels.VerifyResponse response = VerificationFixtures.response("code",
                List.of(VerificationFixtures.item("repo-1", "REPOSITORY", "https://github.com/racer/a", "Go 100%")),
                List.of(VerificationFixtures.agentVerdict("claim:" + go.getId(), "SKILL", "Go", "VERIFIED", true, "repo-1")));

        // A revoke in progress: it holds the candidate lock (as VerificationSourceService.revoke does) and commits later.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> revoker = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            lockRepository.lockById(me.getId()).orElseThrow();
            VerificationSource source = sources.findById(github.getId()).orElseThrow();
            source.setRevokedAt(UtcTime.now());
            sources.saveAndFlush(source);
            locked.countDown();
            pause(300);
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        VerificationResultWriter.Outcome outcome = resultWriter.storeRunResult(runId, response);
        revoker.get(10, TimeUnit.SECONDS);

        assertFalse(outcome.completed(), "the writer waits for the revoke and then sees the source revoked");
        assertEquals("SOURCE_REVOKED", outcome.errorCode());
        assertTrue(verdicts.findAllByRunIdOrderByIdAsc(runId).isEmpty(), "no verdict on a revoked source");
        assertTrue(evidence.findAllByRunIdOrderByIdAsc(runId).isEmpty(), "no evidence on a revoked source");
    }

    // ------------------------------------------------------------------------------------------- R3-B02

    @Test
    void aRefereeAnswerRacingADeclineGets410AndTheFirstOutcomeStays() throws Exception {
        CandidateProfile me = fixtures.candidate("answer-once@cand.test");
        CandidateClaim role = verification.claim(me, ClaimType.EXPERIENCE, "Engineer at Acme");
        String token = requestReference("answer-once@cand.test", role, "once@acme.example");
        VerificationReferenceRequest stored = references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).get(0);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> decliner = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            VerificationReferenceRequest row = references.lockById(stored.getId()).orElseThrow();
            row.setStatus(ReferenceStatus.DECLINED);
            row.setAnsweredAt(UtcTime.now());
            references.saveAndFlush(row);
            locked.countDown();
            pause(300);
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));
        mvc.perform(post(PUBLIC_REFERENCE, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"claimId\":\"claim:" + role.getId() + "\",\"answer\":\"CONFIRM\"}],\"confirmIdentity\":true}"))
                .andExpect(status().isGone());
        decliner.get(10, TimeUnit.SECONDS);

        VerificationReferenceRequest after = references.findById(stored.getId()).orElseThrow();
        assertEquals(ReferenceStatus.DECLINED, after.getStatus(), "the first outcome is never overwritten");
        assertTrue(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.VERIFY_REFERENCE, stored.getId()).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- R3-B03

    @Test
    void anotherOrganisationsRejectionNeitherHidesTheClaimNorItsTierInThisOrganisation() throws Exception {
        Organization orgA = fixtures.organization("Org A");
        HRProfile hrA = fixtures.hrMember(orgA, "hr@org-a.test", Role.HR_MANAGER);
        Organization orgB = fixtures.organization("Org B");
        HRProfile hrB = fixtures.hrMember(orgB, "hr@org-b.test", Role.HR_MANAGER);
        CandidateProfile candidate = fixtures.candidate("two-orgs@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application applicationA = screening.application(candidate, fixtures.job(hrA, "Frontend", JobStatus.PUBLISHED), resume, UtcTime.now());
        screening.application(candidate, fixtures.job(hrB, "UI", JobStatus.PUBLISHED), resume, UtcTime.now());
        CandidateClaim react = verification.claim(candidate, ClaimType.SKILL, "React");
        VerificationSource github = verification.source(candidate, VerificationSourceKind.GITHUB, "two-orgs", true);
        ClaimVerdict verdict = verification.verdict(github, react, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);

        // A corroborated the claim, then B rejected it: the claim's own columns hold B's (latest) rejection.
        claimVerifications.save(claimVerification(react, orgA, EvidenceTier.CORROBORATED));
        claimVerifications.save(claimVerification(react, orgB, EvidenceTier.SELF_CLAIMED));
        CandidateClaim rejected = claims.findById(react.getId()).orElseThrow();
        rejected.setTier(EvidenceTier.SELF_CLAIMED);
        rejected.setVerifiedAt(UtcTime.now());
        rejected.setVerifiedByUserId(hrB.getUser().getId());
        claims.save(rejected);
        verification.mode(orgA.getId(), VerificationMode.VISIBLE);

        mvc.perform(get("/api/v1/hr/applications/{id}/verification", applicationA.getId()).with(staff("hr@org-a.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims", hasSize(1)))
                .andExpect(jsonPath("$.data.claims[0].tier", is("CORROBORATED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].id", is(verdict.getId().intValue())));
        // The candidate still sees (and can verify) the claim.
        mvc.perform(get(BASE + "/ledger").with(candidate("two-orgs@cand.test")))
                .andExpect(jsonPath("$.data[?(@.subjectId == " + react.getId() + ")]", hasSize(1)));
    }

    @Test
    void aRejectionKeepsTheClaimsVerdictOutOfTheRejectingOrganisationsMatchingOnly() throws Exception {
        // Integration pass (R2-H02 + R3-B03 agree): the rejection is per organisation, so B's rejection stops the lift
        // in B's matching only, while A (which confirmed the verdict and never rejected the claim) keeps it.
        Organization orgA = fixtures.organization("Lift A");
        HRProfile hrA = fixtures.hrMember(orgA, "hr@lift-a.test", Role.HR_MANAGER);
        Organization orgB = fixtures.organization("Lift B");
        HRProfile hrB = fixtures.hrMember(orgB, "hr@lift-b.test", Role.HR_MANAGER);
        CandidateProfile candidate = fixtures.candidate("lift@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        com.recruitpro.hr.entity.Job jobA = fixtures.job(hrA, "Frontend", JobStatus.PUBLISHED);
        com.recruitpro.hr.entity.Job jobB = fixtures.job(hrB, "UI", JobStatus.PUBLISHED);
        screening.requirement(jobA, com.recruitpro.hr.entity.RequirementType.SKILL, "React", true, false);
        screening.requirement(jobB, com.recruitpro.hr.entity.RequirementType.SKILL, "React", true, false);
        Application atA = screening.application(candidate, jobA, resume, UtcTime.now());
        Application atB = screening.application(candidate, jobB, resume, UtcTime.now());
        CandidateClaim react = verification.claim(candidate, ClaimType.SKILL, "React");
        VerificationSource github = verification.source(candidate, VerificationSourceKind.GITHUB, "lifter", true);
        ClaimVerdict verdict = verification.verdict(github, react, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);
        for (Organization org : List.of(orgA, orgB)) {
            verification.mode(org.getId(), VerificationMode.VISIBLE);
            ClaimVerdictReview confirm = new ClaimVerdictReview();
            confirm.setVerdictId(verdict.getId());
            confirm.setOrganizationId(org.getId());
            confirm.setDecision(ReviewDecision.CONFIRM);
            reviews.save(confirm);
        }
        claimVerifications.save(claimVerification(react, orgB, EvidenceTier.SELF_CLAIMED));

        assertTrue(liftedFact(matchingService.prepare(atA.getId())), "A keeps the lift");
        assertFalse(liftedFact(matchingService.prepare(atB.getId())), "B rejected the claim: no lift for B");
    }

    private static boolean liftedFact(com.recruitpro.evidence.service.PreparedMatch prepared) {
        assertNotNull(prepared);
        return prepared.request().facts().stream().anyMatch(fact -> fact.id().startsWith("vf:"));
    }

    // ------------------------------------------------------------------------------------------- R3-B04

    @Test
    void referenceRequestsAreCappedPerDayEvenWhenCancelled() throws Exception {
        CandidateProfile me = fixtures.candidate("daily@cand.test");
        CandidateClaim role = verification.claim(me, ClaimType.EXPERIENCE, "Engineer at Acme");
        for (int i = 0; i < ReferenceService.MAX_PER_DAY; i++) {
            VerificationReferenceRequest cancelled = new VerificationReferenceRequest();
            cancelled.setCandidateId(me.getId());
            cancelled.setRefereeName("Referee " + i);
            cancelled.setRefereeEmail("ref" + i + "@acme.example");
            cancelled.setRelationship(ReferenceRelationship.PEER);
            cancelled.setClaimRefsJson("[]");
            cancelled.setTokenHash(String.format("%064d", i));
            cancelled.setStatus(ReferenceStatus.CANCELLED);
            cancelled.setExpiresAt(UtcTime.now().plusDays(14));
            cancelled.setConsentedAt(UtcTime.now());
            references.save(cancelled);
        }
        mvc.perform(post(BASE + "/references").with(candidate("daily@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content(referenceBody(role, "eleventh@acme.example", "Rita Referee")))
                .andExpect(status().isTooManyRequests())
                // Integration pass (R5#3): the oldest request of the window leaves it in about 24 hours.
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("\\d+")))
                .andExpect(result -> assertTrue(Long.parseLong(result.getResponse().getHeader("Retry-After")) > 23 * 3600));
        assertTrue(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("eleventh@acme.example",
                NotificationTemplate.REFERENCE_REQUEST).isEmpty(), "no e-mail is sent");
    }

    // ------------------------------------------------------------------------------------------- R3-B05

    @Test
    void accountDeletionRemovesTheCandidatesReferenceEmailsOnly() throws Exception {
        CandidateProfile me = fixtures.candidate("leaver@cand.test");
        CandidateClaim mine = verification.claim(me, ClaimType.EXPERIENCE, "Engineer at Acme");
        CandidateProfile other = fixtures.candidate("stayer@cand.test");
        CandidateClaim theirs = verification.claim(other, ClaimType.EXPERIENCE, "Analyst at Acme");
        requestReference("leaver@cand.test", mine, "shared-referee@acme.example");
        String otherToken = requestReference("stayer@cand.test", theirs, "shared-referee@acme.example");
        assertEquals(2, notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("shared-referee@acme.example",
                NotificationTemplate.REFERENCE_REQUEST).size());

        privacyService.deleteAllForCandidate(me.getId());

        List<Notification> left = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("shared-referee@acme.example",
                NotificationTemplate.REFERENCE_REQUEST);
        assertEquals(1, left.size(), "the deleted candidate's e-mail (name + link) is gone, even if unsent");
        assertTrue(left.get(0).getBody().contains(otherToken), "another candidate's request to the same referee stays");
    }

    // ------------------------------------------------------------------------------------------- R3-B06, B08

    @Test
    void sourcesAreCappedPerCandidateAndLongHostsDoNotOverflowTheLabel() throws Exception {
        CandidateProfile me = fixtures.candidate("many@cand.test");
        String label = "a".repeat(60);
        String host = label + "." + label + "." + label + "." + label + "." + label + ".example";
        mvc.perform(post(BASE + "/sources").with(candidate("many@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEBSITE\",\"identifier\":\"https://" + host + "/\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayLabel").value(hasLength(300)));
        for (int i = 1; i < VerificationSourceService.MAX_ACTIVE_SOURCES; i++) {
            verification.source(me, VerificationSourceKind.WEBSITE, "https://site" + i + ".example/", false);
        }
        mvc.perform(post(BASE + "/sources").with(candidate("many@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"GITHUB\",\"identifier\":\"one-too-many\",\"consent\":true}"))
                // Integration pass (R5#3): a business limit is 409 SOURCE_LIMIT, not 429.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.startsWith("SOURCE_LIMIT: ")));
        // Revoked sources do not count.
        VerificationSource revoked = sources.findAllByCandidateIdAndKindAndRevokedAtIsNull(me.getId(), VerificationSourceKind.WEBSITE).get(0);
        revoked.setRevokedAt(UtcTime.now());
        sources.save(revoked);
        mvc.perform(post(BASE + "/sources").with(candidate("many@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"GITHUB\",\"identifier\":\"one-too-many\",\"consent\":true}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------- R3-B11

    @Test
    void sharingAConfirmedVerdictAgainReMatchesTheCandidate() throws Exception {
        Organization org = fixtures.organization("Rematch Org");
        HRProfile hr = fixtures.hrMember(org, "hr@rematch.test", Role.HR_MANAGER);
        CandidateProfile candidate = fixtures.candidate("reshare@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        Application application = screening.application(candidate, fixtures.job(hr, "Backend", JobStatus.PUBLISHED), resume, UtcTime.now());
        CandidateClaim go = verification.claim(candidate, ClaimType.SKILL, "Go");
        VerificationSource github = verification.source(candidate, VerificationSourceKind.GITHUB, "reshare", true);
        ClaimVerdict verdict = verification.verdict(github, go, VerdictValue.VERIFIED, true, CandidateVerdictStatus.HIDDEN);
        ClaimVerdictReview confirm = new ClaimVerdictReview();
        confirm.setVerdictId(verdict.getId());
        confirm.setOrganizationId(org.getId());
        confirm.setDecision(ReviewDecision.CONFIRM);
        reviews.save(confirm);
        jobs.deleteAll();

        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("reshare@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHARE\"}"))
                .andExpect(status().isOk());
        assertFalse(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, application.getId()).isEmpty(),
                "the organisation's CONFIRM applies again, so its matching is refreshed");
    }

    // ------------------------------------------------------------------------------------------- R3-B13, B14

    @Test
    void theShadowQueueAndMetricsUseTheVerdictAsItCountsForTheOrganisation() throws Exception {
        Organization org = fixtures.organization("Label Org");
        fixtures.staff(org, "admin@label.test", Role.ADMIN);
        HRProfile hr = fixtures.hrMember(org, "hr@label.test", Role.HR_MANAGER);
        CandidateProfile candidate = fixtures.candidate("labelled@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.application(candidate, fixtures.job(hr, "Analyst", JobStatus.PUBLISHED), resume, UtcTime.now());
        CandidateClaim cert = verification.claim(candidate, ClaimType.CERTIFICATION, "Data Analysis");
        VerificationSource badge = verification.source(candidate, VerificationSourceKind.OPEN_BADGE,
                "https://badges.issuer.example/assertions/1", true);
        ClaimVerdict capped = verification.verdict(badge, cert, VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED);
        capped.setVerifier("badge");
        capped.setDomainTrustJson(VerificationFixtures.domainTrustJson("BADGE_ISSUER", "badges.issuer.example", "WEAK", "VERIFIED"));
        verdicts.save(capped);
        mvc.perform(put("/api/v1/admin/verification/settings")
                        .with(staff("admin@label.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SHADOW\",\"trustedBadgeIssuers\":[\"issuer.example\"]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/verification/shadow-queue").with(staff("hr@label.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].verdictId", is(capped.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].verdict", is("VERIFIED")));
        mvc.perform(post("/api/v1/admin/verification/verdicts/{id}/label", capped.getId()).with(staff("hr@label.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"labelVerdict\":\"PARTLY_VERIFIED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/verification/metrics").with(staff("hr@label.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.labelled", is(1)))
                .andExpect(jsonPath("$.data.falseCorroborationRate", is(1.0)));

        // R3-B14: a verdict whose connector the organisation disabled is not offered, so it cannot be labelled either.
        mvc.perform(put("/api/v1/admin/verification/settings")
                        .with(staff("admin@label.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SHADOW\",\"connectors\":{\"OPEN_BADGE\":false}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/verification/verdicts/{id}/label", capped.getId()).with(staff("hr@label.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"labelVerdict\":\"VERIFIED\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------- R3-B15

    @Test
    void codeHostResultsKeepOneSkillVerdictPerSubject() throws Exception {
        CandidateProfile me = fixtures.candidate("skills-only@cand.test");
        Resume resume = screening.resume(me, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        ResumeAnalysisFact degree = screening.fact(analysis, ResumeFactType.EDUCATION, "BSc Computer Science 2019");
        CandidateClaim go = verification.claim(me, ClaimType.SKILL, "Go");
        VerificationSource github = verification.source(me, VerificationSourceKind.GITHUB, "skills-only", true);
        long runId = startRun("skills-only@cand.test", github.getId());
        when(agentClient.verifyCode(any())).thenReturn(VerificationFixtures.response("code",
                List.of(VerificationFixtures.item("repo-1", "REPOSITORY", "https://github.com/skills-only/a", "Go 100%")),
                List.of(VerificationFixtures.agentVerdict("claim:" + go.getId(), "SKILL", "Go", "VERIFIED", true, "repo-1"),
                        VerificationFixtures.agentVerdict(" claim:" + go.getId() + " ", "SKILL", "Go", "PARTLY_VERIFIED", true, "repo-1"),
                        VerificationFixtures.agentVerdict("fact:" + degree.getId(), "EDUCATION", "BSc", "NOT_CHECKABLE", true))));

        handler.handle(runId);

        List<ClaimVerdict> stored = verdicts.findAllByRunIdOrderByIdAsc(runId);
        assertEquals(1, stored.size(), "one verdict per subject; the never-sent EDUCATION subject is dropped");
        assertEquals(go.getId(), stored.get(0).getSubjectId());
        assertEquals(VerdictValue.VERIFIED, stored.get(0).getVerdict());
    }

    // ------------------------------------------------------------------------------------------- R3-B17

    @Test
    void theDataExportCarriesDomainTrustAndOwnershipDetail() {
        CandidateProfile me = fixtures.candidate("export@cand.test");
        CandidateClaim cert = verification.claim(me, ClaimType.CERTIFICATION, "Data Analysis");
        VerificationSource badge = verification.source(me, VerificationSourceKind.OPEN_BADGE,
                "https://badges.issuer.example/assertions/2", true);
        badge.setOwnershipDetail("The badge recipient matches the candidate");
        sources.save(badge);
        ClaimVerdict verdict = verification.verdict(badge, cert, VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED);
        verdict.setDomainTrustJson(VerificationFixtures.domainTrustJson("BADGE_ISSUER", "badges.issuer.example", "WEAK", "VERIFIED"));
        verdicts.save(verdict);

        VerificationExport export = privacyService.export(me.getId());
        assertEquals("The badge recipient matches the candidate", export.sources().get(0).ownershipDetail());
        assertNotNull(export.verdicts().get(0).domainTrust());
        assertEquals("BADGE_ISSUER", export.verdicts().get(0).domainTrust().path("check").asText());
    }

    // ------------------------------------------------------------------------------------------- R3-B20

    @Test
    void refereeNamesWithLineBreaksAreRefused() throws Exception {
        CandidateProfile me = fixtures.candidate("crlf@cand.test");
        CandidateClaim role = verification.claim(me, ClaimType.EXPERIENCE, "Engineer at Acme");
        mvc.perform(post(BASE + "/references").with(candidate("crlf@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content(referenceBody(role, "crlf@acme.example", "Rita\\r\\nClick https://evil.example")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------- R3-B18

    @Test
    void startingGitHubConnectionsIsLimitedPerCandidate() throws Exception {
        // Integration pass (R3-B18): every start stores a state row; at most 20 an hour per candidate, then 429.
        fixtures.candidate("oauth-many@cand.test");
        fixtures.candidate("oauth-other@cand.test");
        for (int i = 0; i < GitHubOAuthService.MAX_STARTS_PER_HOUR; i++) {
            mvc.perform(get(BASE + "/oauth/github/start").with(candidate("oauth-many@cand.test"))).andExpect(status().isOk());
        }
        mvc.perform(get(BASE + "/oauth/github/start").with(candidate("oauth-many@cand.test")))
                .andExpect(status().isTooManyRequests())
                .andExpect(result -> {
                    long seconds = Long.parseLong(result.getResponse().getHeader("Retry-After"));
                    assertTrue(seconds > 0 && seconds <= 3600, "Retry-After " + seconds);
                });
        mvc.perform(get(BASE + "/oauth/github/start").with(candidate("oauth-other@cand.test"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------- R3-B21

    @Test
    void anUnexpectedGitHubClientFailureStillRedirectsToThePortal() throws Exception {
        fixtures.candidate("oauth-fail@cand.test");
        MvcResult started = mvc.perform(get(BASE + "/oauth/github/start").with(candidate("oauth-fail@cand.test")))
                .andExpect(status().isOk()).andReturn();
        String state = UriComponentsBuilder.fromUriString(data(started).path("authorizeUrl").asText()).build()
                .getQueryParams().getFirst("state");
        String setCookie = started.getResponse().getHeader("Set-Cookie");
        String nonce = setCookie.substring(GitHubOAuthBindingCookie.NAME.length() + 1, setCookie.indexOf(';'));
        when(gitHubClient.exchangeCode(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("unexpected"));

        mvc.perform(get("/api/v1/verification/oauth/github/callback").param("code", "the-code").param("state", state)
                        .cookie(new Cookie(GitHubOAuthBindingCookie.NAME, nonce)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/verification?error=GITHUB_UNAVAILABLE"));
    }

    // -------------------------------------------------------------------------------------------- helpers

    private long startRun(String email, Long sourceId) throws Exception {
        return data(mvc.perform(post(BASE + "/sources/{id}/runs", sourceId).with(candidate(email)))
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
    }

    /** Creates a reference request through the API and returns the token of the e-mailed link. */
    private String requestReference(String candidateEmail, CandidateClaim claim, String refereeEmail) throws Exception {
        mvc.perform(post(BASE + "/references").with(candidate(candidateEmail)).contentType(MediaType.APPLICATION_JSON)
                        .content(referenceBody(claim, refereeEmail, "Rita Referee")))
                .andExpect(status().isOk());
        List<Notification> sent = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(refereeEmail,
                NotificationTemplate.REFERENCE_REQUEST);
        Matcher matcher = REFERENCE_LINK.matcher(sent.get(sent.size() - 1).getBody());
        assertTrue(matcher.find(), "the e-mail carries the referee link");
        return matcher.group(1);
    }

    private static String referenceBody(CandidateClaim claim, String refereeEmail, String refereeName) {
        return "{\"refereeName\":\"" + refereeName + "\",\"refereeEmail\":\"" + refereeEmail + "\",\"relationship\":\"MANAGER\","
                + "\"employerName\":\"Acme\",\"employerDomain\":\"acme.example\",\"claims\":["
                + "{\"subjectType\":\"CANDIDATE_CLAIM\",\"subjectId\":" + claim.getId() + "}],\"consent\":true}";
    }

    private static CandidateClaimVerification claimVerification(CandidateClaim claim, Organization organization, EvidenceTier tier) {
        CandidateClaimVerification row = new CandidateClaimVerification();
        row.setClaimId(claim.getId());
        row.setOrganizationId(organization.getId());
        row.setTier(tier);
        row.setVerifiedAt(UtcTime.now());
        return row;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
