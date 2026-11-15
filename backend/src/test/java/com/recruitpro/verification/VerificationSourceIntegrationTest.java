package com.recruitpro.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.RunRequester;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.VerificationAuditActions;
import com.recruitpro.verification.service.VerificationStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Source lifecycle: consent, duplicates, documents, ownership checks and revoke cleanup (plan v2 section 5). */
class VerificationSourceIntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/candidates/me/verification";

    @Autowired VerificationSourceRepository sources;
    @Autowired VerificationRunRepository runs;
    @Autowired EvidenceItemRepository evidence;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired VerificationStorage storage;
    @Autowired AuditEventRepository audit;

    @Test
    void connectingNeedsConsentAndRejectsDuplicates() throws Exception {
        fixtures.candidate("owner@cand.test");
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"GITHUB\",\"identifier\":\"octo-cat\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"GITHUB\",\"identifier\":\"https://github.com/Octo-Cat\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind", is("GITHUB")))
                .andExpect(jsonPath("$.data.identifier", is("octo-cat")))
                .andExpect(jsonPath("$.data.ownershipStatus", is("UNPROVEN")))
                .andExpect(jsonPath("$.data.ownershipMethod", nullValue()))
                .andExpect(jsonPath("$.data.challengeCode", containsString("rp-")))
                .andExpect(jsonPath("$.data.ownershipInstructions[0]", containsString("Connect with GitHub")))
                .andExpect(jsonPath("$.data.lastRun", nullValue()))
                .andExpect(jsonPath("$.data.consentedAt").exists());
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"GITHUB\",\"identifier\":\"OCTO-CAT\",\"consent\":true}")).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WEBSITE\",\"identifier\":\"ftp://example.test\",\"consent\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"DOCUMENT\",\"identifier\":\"cv.pdf\",\"consent\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEBSITE\",\"identifier\":\"https://Portfolio.Example.test/about\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifier", is("https://portfolio.example.test/about")))
                .andExpect(jsonPath("$.data.ownershipInstructions", hasSize(4)));
        mvc.perform(post(BASE + "/sources").with(candidate("owner@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"OPEN_BADGE\",\"credential\":\"eyJhbGciOiJFZERTQSJ9.e30.c2ln\",\"consent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifier", containsString("credential:")))
                .andExpect(jsonPath("$.data.ownershipMethod", is("ISSUER_SIGNATURE")));
        mvc.perform(get(BASE + "/sources").with(candidate("owner@cand.test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].accessTokenEnc").doesNotExist());
        assertEquals(3, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.SOURCE_CONNECTED).size());
        mvc.perform(get(BASE + "/sources").with(staff("someone@org.test", "RECRUITER"))).andExpect(status().isForbidden());
    }

    @Test
    void documentsAreCheckedByMagicBytesAndStored() throws Exception {
        CandidateProfile me = fixtures.candidate("doc@cand.test");
        MockMultipartFile pdf = new MockMultipartFile("file", "certificate.pdf", "application/pdf",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII));
        mvc.perform(multipart(BASE + "/sources/documents").file(pdf).with(candidate("doc@cand.test")))
                .andExpect(status().isBadRequest());
        JsonNode created = data(mvc.perform(multipart(BASE + "/sources/documents").file(pdf).param("consent", "true")
                        .with(candidate("doc@cand.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind", is("DOCUMENT")))
                .andExpect(jsonPath("$.data.identifier", is("certificate.pdf")))
                .andReturn());
        VerificationSource stored = sources.findById(created.path("id").asLong()).orElseThrow();
        assertNotNull(stored.getFilePath());
        assertTrue(storage.exists(stored.getFilePath()));
        assertEquals(me.getId(), stored.getCandidateId());

        MockMultipartFile fake = new MockMultipartFile("file", "slides.pptx", "application/octet-stream",
                "%PDF-1.7 not a zip".getBytes(StandardCharsets.US_ASCII));
        mvc.perform(multipart(BASE + "/sources/documents").file(fake).param("consent", "true").with(candidate("doc@cand.test")))
                .andExpect(status().isBadRequest());
        MockMultipartFile exe = new MockMultipartFile("file", "tool.exe", "application/octet-stream", new byte[]{0x4D, 0x5A});
        mvc.perform(multipart(BASE + "/sources/documents").file(exe).param("consent", "true").with(candidate("doc@cand.test")))
                .andExpect(status().isBadRequest());
        MockMultipartFile docx = new MockMultipartFile("file", "letter.docx", "application/octet-stream", zip("word/document.xml"));
        mvc.perform(multipart(BASE + "/sources/documents").file(docx).param("consent", "true").with(candidate("doc@cand.test")))
                .andExpect(status().isOk());
        MockMultipartFile wrongZip = new MockMultipartFile("file", "deck.pptx", "application/octet-stream", zip("word/document.xml"));
        mvc.perform(multipart(BASE + "/sources/documents").file(wrongZip).param("consent", "true").with(candidate("doc@cand.test")))
                .andExpect(status().isBadRequest());
        // Same file name again: duplicate.
        mvc.perform(multipart(BASE + "/sources/documents").file(pdf).param("consent", "true").with(candidate("doc@cand.test")))
                .andExpect(status().isConflict());
        // Ownership of a document cannot be proven.
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", stored.getId()).with(candidate("doc@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PAGE_CODE\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void ownershipCheckCallsTheAgentAndStoresTheProof() throws Exception {
        CandidateProfile me = fixtures.candidate("bio@cand.test");
        fixtures.candidate("other@cand.test");
        VerificationSource source = verification.source(me, VerificationSourceKind.GITHUB, "bio-user", false);
        String proof = "Bio: hello rp-test-challenge-code-0001";
        when(agentClient.checkOwnership(any())).thenReturn(new AgentModels.OwnershipCheckResponse(false,
                "PROFILE_BIO_CODE", "the code was not found in the profile bio", null));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("bio@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PROFILE_BIO_CODE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownershipStatus", is("FAILED")))
                .andExpect(jsonPath("$.data.ownershipDetail", containsString("not found")));

        when(agentClient.checkOwnership(any())).thenReturn(new AgentModels.OwnershipCheckResponse(true, "PROFILE_BIO_CODE",
                "the code is in the profile bio",
                com.recruitpro.support.VerificationFixtures.item("ownership-1", "OWNERSHIP_PROOF", "https://github.com/bio-user", proof)));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("bio@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PROFILE_BIO_CODE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownershipStatus", is("PROVEN")))
                .andExpect(jsonPath("$.data.ownershipMethod", is("PROFILE_BIO_CODE")))
                .andExpect(jsonPath("$.data.ownershipInstructions", hasSize(0)));
        verify(agentClient, org.mockito.Mockito.atLeastOnce()).checkOwnership(
                new AgentModels.OwnershipCheckRequest("PROFILE_BIO_CODE", "github:bio-user", "rp-test-challenge-code-0001"));
        EvidenceItem stored = evidence.findAllBySourceId(source.getId()).get(0);
        assertEquals(EvidenceItemKind.OWNERSHIP_PROOF, stored.getKind());
        assertNull(stored.getRunId());
        assertTrue(storage.exists(stored.getSnapshotPath()));
        assertEquals(1, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.OWNERSHIP_PROVEN).size());

        // A later failed check keeps PROVEN; the wrong method for the kind is 400; another candidate gets 404.
        when(agentClient.checkOwnership(any())).thenReturn(new AgentModels.OwnershipCheckResponse(false, "PROFILE_BIO_CODE", "gone", null));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("bio@cand.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PROFILE_BIO_CODE\"}"))
                .andExpect(jsonPath("$.data.ownershipStatus", is("PROVEN")));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("bio@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"DNS_TXT\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("other@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PROFILE_BIO_CODE\"}")).andExpect(status().isNotFound());
    }

    @Test
    void websiteChecksSendTheHostOrThePage() throws Exception {
        CandidateProfile me = fixtures.candidate("site@cand.test");
        VerificationSource source = verification.source(me, VerificationSourceKind.WEBSITE, "https://me.example.test/about", false);
        when(agentClient.checkOwnership(any())).thenReturn(new AgentModels.OwnershipCheckResponse(false, "DNS_TXT", "no record", null));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("site@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"DNS_TXT\"}")).andExpect(status().isOk());
        verify(agentClient).checkOwnership(new AgentModels.OwnershipCheckRequest("DNS_TXT", "me.example.test", "rp-test-challenge-code-0001"));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("site@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PAGE_CODE\"}")).andExpect(status().isOk());
        verify(agentClient).checkOwnership(new AgentModels.OwnershipCheckRequest("PAGE_CODE", "https://me.example.test/about",
                "rp-test-challenge-code-0001"));
        mvc.perform(post(BASE + "/sources/{id}/ownership-check", source.getId()).with(candidate("site@cand.test"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"PROFILE_BIO_CODE\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void revokeDeletesTokenFilesAndEvidenceAndWithdrawsVerdicts() throws Exception {
        CandidateProfile me = fixtures.candidate("revoke@cand.test");
        CandidateClaim claim = verification.claim(me, ClaimType.SKILL, "Go");
        VerificationSource source = verification.source(me, VerificationSourceKind.GITHUB, "gopher", true);
        source.setAccessTokenEnc("v1:not-a-real-ciphertext");
        source.setTokenScopes("read:user");
        source = sources.save(source);
        VerificationRun done = run(source, VerificationRunStatus.COMPLETED);
        VerificationRun pending = run(source, VerificationRunStatus.PENDING);
        String snapshotKey = storage.store("snapshot".getBytes(StandardCharsets.UTF_8));
        EvidenceItem item = new EvidenceItem();
        item.setRunId(done.getId());
        item.setSourceId(source.getId());
        item.setCandidateId(me.getId());
        item.setItemKey("repo-1");
        item.setKind(EvidenceItemKind.REPOSITORY);
        item.setRetrievedAt(UtcTime.now());
        item.setContentHash("sha256:" + "0".repeat(64));
        item.setSnapshotPath(snapshotKey);
        evidence.save(item);
        ClaimVerdict verdict = verification.verdict(source, claim, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);

        mvc.perform(delete(BASE + "/sources/{id}", source.getId()).with(candidate("revoke@cand.test"))).andExpect(status().isOk());

        VerificationSource revoked = sources.findById(source.getId()).orElseThrow();
        assertNotNull(revoked.getRevokedAt());
        assertNull(revoked.getAccessTokenEnc());
        assertNull(revoked.getTokenScopes());
        assertTrue(evidence.findAllBySourceId(source.getId()).isEmpty());
        assertFalse(storage.exists(snapshotKey), "snapshots are deleted after the commit");
        ClaimVerdict withdrawn = verdicts.findById(verdict.getId()).orElseThrow();
        assertEquals(CandidateVerdictStatus.WITHDRAWN, withdrawn.getCandidateStatus());
        assertFalse(withdrawn.isCurrent());
        assertEquals(VerificationRunStatus.FAILED, runs.findById(pending.getId()).orElseThrow().getStatus());
        assertEquals("SOURCE_REVOKED", runs.findById(pending.getId()).orElseThrow().getErrorCode());
        assertEquals(1, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.SOURCE_REVOKED).size());
        mvc.perform(get(BASE + "/sources").with(candidate("revoke@cand.test"))).andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(delete(BASE + "/sources/{id}", source.getId()).with(candidate("revoke@cand.test"))).andExpect(status().isNotFound());
        // The same account can be connected again after revoking.
        mvc.perform(post(BASE + "/sources").with(candidate("revoke@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"GITHUB\",\"identifier\":\"gopher\",\"consent\":true}")).andExpect(status().isOk());
        verify(agentClient, never()).verifyCode(any());
        assertEquals(OwnershipStatus.PROVEN, revoked.getOwnershipStatus());
        assertEquals(OwnershipMethod.PROFILE_BIO_CODE, revoked.getOwnershipMethod());
    }

    private VerificationRun run(VerificationSource source, VerificationRunStatus status) {
        VerificationRun run = new VerificationRun();
        run.setSourceId(source.getId());
        run.setCandidateId(source.getCandidateId());
        run.setStatus(status);
        run.setRequestedBy(RunRequester.CANDIDATE);
        return runs.save(run);
    }

    private static byte[] zip(String entryName) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("<doc/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
