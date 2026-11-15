package com.recruitpro.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.client.AgentServiceException;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.VerificationStorage;
import com.recruitpro.verification.service.VerifySourceJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** VERIFY_SOURCE: evidence with hash check, append-only verdicts, ownership cap, failures and run limits. */
class VerifySourceJobHandlerIntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/candidates/me/verification";

    @Autowired ScreeningFixtures screening;
    @Autowired VerifySourceJobHandler handler;
    @Autowired VerificationRunRepository runs;
    @Autowired VerificationSourceRepository sources;
    @Autowired EvidenceItemRepository evidence;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired VerificationStorage storage;
    @Autowired BackgroundJobRepository jobs;
    @Autowired NotificationRepository notifications;

    CandidateProfile me;
    ResumeAnalysisFact javaFact;
    ResumeAnalysisFact degreeFact;
    CandidateClaim goClaim;
    VerificationSource github;

    @BeforeEach
    void setUp() {
        me = fixtures.candidate("runner@cand.test");
        Resume resume = screening.resume(me, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        javaFact = screening.fact(analysis, ResumeFactType.SKILL, "Java");
        degreeFact = screening.fact(analysis, ResumeFactType.EDUCATION, "BSc Computer Science 2019");
        screening.fact(analysis, ResumeFactType.EMAIL, "runner@cand.test");
        goClaim = verification.claim(me, ClaimType.SKILL, "Go");
        github = verification.source(me, VerificationSourceKind.GITHUB, "runner", true);
    }

    @Test
    void aRunStoresEvidenceSnapshotsAndAwaitingVerdictsAndNotifiesTheCandidate() throws Exception {
        long runId = startRun(github.getId());
        assertTrue(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.VERIFY_SOURCE, runId).stream()
                .anyMatch(job -> job.getStatus() == BackgroundJobStatus.PENDING));
        String repoSnapshot = "Repository A: Java 80%, Go 20%";
        when(agentClient.verifyCode(any())).thenReturn(VerificationFixtures.response("code",
                List.of(VerificationFixtures.item("repo-1", "REPOSITORY", "https://github.com/runner/a", repoSnapshot),
                        VerificationFixtures.item("profile", "PROFILE", "https://github.com/runner", "profile text")),
                List.of(VerificationFixtures.agentVerdict("fact:" + javaFact.getId(), "SKILL", "Java", "VERIFIED", true, "repo-1"),
                        VerificationFixtures.agentVerdict("claim:" + goClaim.getId(), "SKILL", "Go", "PARTLY_VERIFIED", true, "repo-1"),
                        VerificationFixtures.agentVerdict("claim:999999", "SKILL", "Unknown", "VERIFIED", true, "repo-1"))));

        handler.handle(runId);

        ArgumentCaptor<AgentModels.CodeVerifyRequest> sent = ArgumentCaptor.forClass(AgentModels.CodeVerifyRequest.class);
        verify(agentClient).verifyCode(sent.capture());
        assertEquals("github", sent.getValue().provider());
        assertEquals("runner", sent.getValue().login());
        assertTrue(sent.getValue().ownershipProven());
        List<String> claimIds = sent.getValue().claims().stream().map(AgentModels.Claim::id).toList();
        assertEquals(List.of("fact:" + javaFact.getId(), "claim:" + goClaim.getId()), claimIds,
                "code hosts get SKILL subjects only; contact facts never");

        VerificationRun run = runs.findById(runId).orElseThrow();
        assertEquals(VerificationRunStatus.COMPLETED, run.getStatus());
        assertEquals("code", run.getVerifier());
        List<EvidenceItem> items = evidence.findAllByRunIdOrderByIdAsc(runId);
        assertEquals(2, items.size());
        EvidenceItem repo = items.get(0);
        assertEquals("repo-1", repo.getItemKey());
        assertEquals(new String(storage.load(repo.getSnapshotPath()), StandardCharsets.UTF_8), repoSnapshot);
        List<ClaimVerdict> stored = verdicts.findAllByRunIdOrderByIdAsc(runId);
        assertEquals(2, stored.size(), "verdicts for subjects that were not sent are dropped");
        ClaimVerdict javaVerdict = stored.get(0);
        assertEquals(SubjectType.RESUME_FACT, javaVerdict.getSubjectType());
        assertEquals(javaFact.getId(), javaVerdict.getSubjectId());
        assertEquals(VerdictValue.VERIFIED, javaVerdict.getVerdict());
        assertTrue(javaVerdict.isOwnershipProven());
        assertEquals(CandidateVerdictStatus.AWAITING_CANDIDATE, javaVerdict.getCandidateStatus());
        assertEquals("[" + repo.getId() + "]", javaVerdict.getEvidenceItemIdsJson());
        assertEquals(1, notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("runner@cand.test",
                NotificationTemplate.VERIFICATION_READY).stream().filter(n -> n.getChannel().name().equals("EMAIL")).count());

        // A second run supersedes the current verdict of the same subject and source (append-only).
        long second = startRun(github.getId());
        when(agentClient.verifyCode(any())).thenReturn(VerificationFixtures.response("code",
                List.of(VerificationFixtures.item("repo-1", "REPOSITORY", "https://github.com/runner/a", repoSnapshot)),
                List.of(VerificationFixtures.agentVerdict("fact:" + javaFact.getId(), "SKILL", "Java", "PARTLY_VERIFIED", true, "repo-1"))));
        handler.handle(second);
        ClaimVerdict old = verdicts.findById(javaVerdict.getId()).orElseThrow();
        assertFalse(old.isCurrent());
        assertEquals(CandidateVerdictStatus.SUPERSEDED, old.getCandidateStatus());
        ClaimVerdict current = verdicts.findAllByRunIdOrderByIdAsc(second).get(0);
        assertTrue(current.isCurrent());
        assertEquals(CandidateVerdictStatus.AWAITING_CANDIDATE, current.getCandidateStatus());
        assertTrue(verdicts.findById(stored.get(1).getId()).orElseThrow().isCurrent(), "other subjects keep their verdict");
    }

    @Test
    void aHashMismatchRejectsTheWholeResponse() throws Exception {
        long runId = startRun(github.getId());
        AgentModels.EvidenceItem good = VerificationFixtures.item("repo-1", "REPOSITORY", null, "real text");
        AgentModels.EvidenceItem tampered = new AgentModels.EvidenceItem("repo-2", "REPOSITORY", null, "t", "2026-11-15T10:00:00Z",
                VerificationFixtures.item("x", "PAGE", null, "other text").contentHash(), "", "tampered text", null);
        when(agentClient.verifyCode(any())).thenReturn(VerificationFixtures.response("code", List.of(good, tampered),
                List.of(VerificationFixtures.agentVerdict("fact:" + javaFact.getId(), "SKILL", "Java", "VERIFIED", true, "repo-2"))));
        handler.handle(runId);
        VerificationRun run = runs.findById(runId).orElseThrow();
        assertEquals(VerificationRunStatus.FAILED, run.getStatus());
        assertEquals("EVIDENCE_HASH_MISMATCH", run.getErrorCode());
        assertTrue(evidence.findAllByRunIdOrderByIdAsc(runId).isEmpty());
        assertTrue(verdicts.findAllByRunIdOrderByIdAsc(runId).isEmpty());
    }

    @Test
    void theBackendCapsVerdictsOfUnprovenSources() throws Exception {
        VerificationSource site = verification.source(me, VerificationSourceKind.WEBSITE, "https://runner.example.test", false);
        long runId = startRun(site.getId());
        when(agentClient.verifyWeb(any())).thenReturn(VerificationFixtures.response("web",
                List.of(VerificationFixtures.item("page-1", "PAGE", "https://runner.example.test", "I studied BSc Computer Science 2019")),
                List.of(VerificationFixtures.agentVerdict("fact:" + degreeFact.getId(), "EDUCATION", "BSc", "VERIFIED", true, "page-1"))));
        handler.handle(runId);
        ArgumentCaptor<AgentModels.WebVerifyRequest> sent = ArgumentCaptor.forClass(AgentModels.WebVerifyRequest.class);
        verify(agentClient).verifyWeb(sent.capture());
        assertFalse(sent.getValue().ownershipProven());
        assertEquals("Test Candidate", sent.getValue().holderName());
        assertEquals(3, sent.getValue().claims().size(), "websites get every non-contact subject");
        ClaimVerdict capped = verdicts.findAllByRunIdOrderByIdAsc(runId).get(0);
        assertEquals(VerdictValue.PARTLY_VERIFIED, capped.getVerdict(), "ownership not proven: at most PARTLY_VERIFIED");
        assertFalse(capped.isOwnershipProven());
    }

    @Test
    void agentErrorsFailTheRunWithTheirCodeAndOutagesAreRetried() throws Exception {
        long coded = startRun(github.getId());
        when(agentClient.verifyCode(any())).thenThrow(new AgentServiceException(502, "RATE_LIMITED", "GitHub rate limit"));
        handler.handle(coded);
        assertEquals("RATE_LIMITED", runs.findById(coded).orElseThrow().getErrorCode());
        assertEquals(VerificationRunStatus.FAILED, runs.findById(coded).orElseThrow().getStatus());

        long outage = startRun(github.getId());
        doThrow(new ResourceAccessException("connection refused")).when(agentClient).verifyCode(any());
        assertThrows(IllegalStateException.class, () -> handler.handle(outage), "transport errors are retried");
        assertEquals(VerificationRunStatus.RUNNING, runs.findById(outage).orElseThrow().getStatus());
        handler.onDeadLetter(outage, "retries exhausted");
        VerificationRun dead = runs.findById(outage).orElseThrow();
        assertEquals(VerificationRunStatus.FAILED, dead.getStatus());
        assertEquals(VerifySourceJobHandler.UNAVAILABLE, dead.getErrorCode());

        long rejected = startRun(github.getId());
        doThrow(new AgentServiceException(401, null, null)).when(agentClient).verifyCode(any());
        handler.handle(rejected);
        assertEquals(VerifySourceJobHandler.REJECTED, runs.findById(rejected).orElseThrow().getErrorCode());
    }

    @Test
    void oneActiveRunPerSourceAndTenRunsPerDay() throws Exception {
        startRun(github.getId());
        mvc.perform(post(BASE + "/sources/{id}/runs", github.getId()).with(candidate("runner@cand.test")))
                .andExpect(status().isConflict());
        // Finish it, then use up the daily budget on other sources.
        VerificationRun first = runs.findFirstBySourceIdOrderByIdDesc(github.getId()).orElseThrow();
        first.setStatus(VerificationRunStatus.COMPLETED);
        runs.save(first);
        for (int i = 0; i < 9; i++) {
            VerificationRun run = new VerificationRun();
            run.setSourceId(github.getId());
            run.setCandidateId(me.getId());
            run.setStatus(VerificationRunStatus.FAILED);
            runs.save(run);
        }
        mvc.perform(post(BASE + "/sources/{id}/runs", github.getId()).with(candidate("runner@cand.test")))
                .andExpect(status().isTooManyRequests());
        fixtures.candidate("someone@cand.test");
        mvc.perform(post(BASE + "/sources/{id}/runs", github.getId()).with(candidate("someone@cand.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRunOfARevokedSourceFailsWithoutCallingTheAgent() throws Exception {
        long runId = startRun(github.getId());
        VerificationSource source = sources.findById(github.getId()).orElseThrow();
        source.setRevokedAt(com.recruitpro.common.time.UtcTime.now());
        sources.save(source);
        handler.handle(runId);
        assertEquals("SOURCE_REVOKED", runs.findById(runId).orElseThrow().getErrorCode());
        assertNull(runs.findById(runId).orElseThrow().getVerifier());
    }

    @Test
    void badgeRunsSendTheCandidatesProvenWebsiteDomainsAndStoreDomainTrust() throws Exception {
        VerificationSource badge = verification.source(me, VerificationSourceKind.OPEN_BADGE,
                "https://badges.runner-site.example/assertions/1", true);
        verification.source(me, VerificationSourceKind.WEBSITE, "https://runner-site.example/", true);
        verification.source(me, VerificationSourceKind.WEBSITE, "https://not-mine.example/", false);
        long runId = startRun(badge.getId());
        String trust = VerificationFixtures.domainTrustJson("BADGE_ISSUER", "badges.runner-site.example", "UNTRUSTED", "VERIFIED");
        when(agentClient.verifyBadge(any())).thenReturn(VerificationFixtures.response("badge",
                List.of(VerificationFixtures.item("badge-1", "BADGE", "https://badges.runner-site.example/assertions/1", "{}")),
                List.of(VerificationFixtures.withDomainTrust(VerificationFixtures.agentVerdict("claim:" + goClaim.getId(),
                        "SKILL", "Go", "PARTLY_VERIFIED", true, "badge-1"), mapper.readTree(trust)))));

        handler.handle(runId);

        ArgumentCaptor<AgentModels.BadgeVerifyRequest> sent = ArgumentCaptor.forClass(AgentModels.BadgeVerifyRequest.class);
        verify(agentClient).verifyBadge(sent.capture());
        assertEquals("https://badges.runner-site.example/assertions/1", sent.getValue().url());
        assertEquals(List.of("runner-site.example"), sent.getValue().candidateDomains(), "only PROVEN websites");
        ClaimVerdict stored = verdicts.findAllByRunIdOrderByIdAsc(runId).get(0);
        assertEquals(VerdictValue.PARTLY_VERIFIED, stored.getVerdict());
        JsonNode storedTrust = mapper.readTree(stored.getDomainTrustJson());
        assertEquals("UNTRUSTED", storedTrust.path("status").asText());
        assertEquals("VERIFIED", storedTrust.path("cappedFrom").asText());
    }

    private long startRun(Long sourceId) throws Exception {
        JsonNode run = data(mvc.perform(post(BASE + "/sources/{id}/runs", sourceId).with(candidate("runner@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        return run.path("id").asLong();
    }
}
