package com.recruitpro.verification;

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
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.service.VerificationAuditActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The candidate's Claim Ledger, verdict detail and Share / Hide / Re-check responses. */
class CandidateLedgerIntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/candidates/me/verification";

    @Autowired ScreeningFixtures screening;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired EvidenceItemRepository evidence;
    @Autowired VerificationRunRepository runs;
    @Autowired BackgroundJobRepository jobs;
    @Autowired AuditEventRepository audit;

    CandidateProfile me;
    ResumeAnalysisFact javaFact;
    CandidateClaim awsClaim;
    VerificationSource source;
    ClaimVerdict verdict;
    EvidenceItem item;

    @BeforeEach
    void setUp() {
        me = fixtures.candidate("ledger@cand.test");
        fixtures.candidate("stranger@cand.test");
        Resume resume = screening.resume(me, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        javaFact = screening.fact(analysis, ResumeFactType.SKILL, "Java");
        screening.fact(analysis, ResumeFactType.PHONE, "+91 99999 99999");
        Resume pending = screening.resume(me, "draft.pdf");
        ResumeAnalysis waiting = screening.analysis(pending, ResumeAnalysisStatus.PENDING);
        screening.fact(waiting, ResumeFactType.SKILL, "Cobol");
        awsClaim = verification.claim(me, ClaimType.CERTIFICATION, "AWS Solutions Architect");
        source = verification.source(me, VerificationSourceKind.OPEN_BADGE, "https://badges.example.test/a/1", true);
        item = new EvidenceItem();
        item.setSourceId(source.getId());
        item.setCandidateId(me.getId());
        item.setItemKey("badge-1");
        item.setKind(EvidenceItemKind.BADGE);
        item.setUrl("https://badges.example.test/a/1");
        item.setTitle("AWS badge");
        item.setRetrievedAt(UtcTime.now());
        item.setContentHash("sha256:" + "1".repeat(64));
        item.setExcerpt("Issued to the candidate");
        item = evidence.save(item);
        ClaimVerdict created = verification.verdict(source, awsClaim, VerdictValue.VERIFIED, true, CandidateVerdictStatus.AWAITING_CANDIDATE);
        created.setVerifier("badge");
        created.setEvidenceItemIdsJson("[" + item.getId() + "]");
        created.setIntegrityNotesJson("[{\"code\":\"STATUS_LIST_UNVERIFIED\",\"text\":\"Revocation list not signed.\",\"evidenceKeys\":[\"badge-1\"]}]");
        created.setProbeQuestion(null);
        verdict = verdicts.save(created);
    }

    @Test
    void theLedgerListsCurrentClaimsWithTheirVerdicts() throws Exception {
        mvc.perform(get(BASE + "/ledger").with(candidate("ledger@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].subjectType", is("RESUME_FACT")))
                .andExpect(jsonPath("$.data[0].subjectId", is(javaFact.getId().intValue())))
                .andExpect(jsonPath("$.data[0].claimType", is("SKILL")))
                .andExpect(jsonPath("$.data[0].value", is("Java")))
                .andExpect(jsonPath("$.data[0].tier", is("RESUME_STATED")))
                .andExpect(jsonPath("$.data[0].verdicts", hasSize(0)))
                .andExpect(jsonPath("$.data[1].subjectType", is("CANDIDATE_CLAIM")))
                .andExpect(jsonPath("$.data[1].tier", is("SELF_CLAIMED")))
                .andExpect(jsonPath("$.data[1].verdicts[0].id", is(verdict.getId().intValue())))
                .andExpect(jsonPath("$.data[1].verdicts[0].verifier", is("badge")))
                .andExpect(jsonPath("$.data[1].verdicts[0].verdict", is("VERIFIED")))
                .andExpect(jsonPath("$.data[1].verdicts[0].candidateStatus", is("AWAITING_CANDIDATE")))
                .andExpect(jsonPath("$.data[1].verdicts[0].sourceLabel", is(source.getDisplayLabel())));
        mvc.perform(get(BASE + "/ledger").with(candidate("stranger@cand.test"))).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void theDetailHasReasonsNotesAndFullEvidence() throws Exception {
        mvc.perform(get(BASE + "/verdicts/{id}", verdict.getId()).with(candidate("ledger@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons[0].text", is("Repository A uses it.")))
                .andExpect(jsonPath("$.data.reasons[0].evidenceKeys[0]", is("repo-1")))
                .andExpect(jsonPath("$.data.integrityNotes[0].code", is("STATUS_LIST_UNVERIFIED")))
                .andExpect(jsonPath("$.data.probeQuestion", nullValue()))
                .andExpect(jsonPath("$.data.summary", hasSize(0)))
                .andExpect(jsonPath("$.data.sourceKind", is("OPEN_BADGE")))
                .andExpect(jsonPath("$.data.evidence[0].key", is("badge-1")))
                .andExpect(jsonPath("$.data.evidence[0].url", is("https://badges.example.test/a/1")))
                .andExpect(jsonPath("$.data.evidence[0].contentHash", is("sha256:" + "1".repeat(64))))
                .andExpect(jsonPath("$.data.evidence[0].retrievedAt").exists());
        mvc.perform(get(BASE + "/verdicts/{id}", verdict.getId()).with(candidate("stranger@cand.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shareHideAndRecheck() throws Exception {
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHARE\",\"note\":\"Renewed in 2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateStatus", is("SHARED")))
                .andExpect(jsonPath("$.data.candidateNote", is("Renewed in 2026")))
                .andExpect(jsonPath("$.data.candidateRespondedAt").exists());
        assertEquals(1, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.VERDICT_SHARED).size());
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"HIDE\"}"))
                .andExpect(jsonPath("$.data.candidateStatus", is("HIDDEN")));
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PUBLISH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("stranger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHARE\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RECHECK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateStatus", is("HIDDEN")));
        Long runId = runs.findFirstBySourceIdOrderByIdDesc(source.getId()).orElseThrow().getId();
        assertTrue(jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.VERIFY_SOURCE, runId).size() == 1,
                "RECHECK queues a new run of the verdict's source");
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RECHECK\"}"))
                .andExpect(status().isConflict());

        // A superseded verdict can no longer be shared.
        ClaimVerdict superseded = verdicts.findById(verdict.getId()).orElseThrow();
        superseded.setCurrent(false);
        superseded.setCandidateStatus(CandidateVerdictStatus.SUPERSEDED);
        verdicts.save(superseded);
        mvc.perform(post(BASE + "/verdicts/{id}/response", verdict.getId()).with(candidate("ledger@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHARE\"}"))
                .andExpect(status().isBadRequest());
    }
}
