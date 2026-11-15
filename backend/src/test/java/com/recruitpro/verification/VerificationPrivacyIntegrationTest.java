package com.recruitpro.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.ReferenceRelationship;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import com.recruitpro.verification.service.VerificationStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The data export includes verification data (never tokens); account deletion removes it, files included. */
class VerificationPrivacyIntegrationTest extends VerificationIntegrationTestBase {

    private static final String EMAIL = "private@cand.test";
    private static final String PASSWORD = "Correct-Horse-9";

    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired VerificationSourceRepository sources;
    @Autowired EvidenceItemRepository evidence;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired ClaimVerdictReviewRepository reviews;
    @Autowired VerificationReferenceRequestRepository references;
    @Autowired VerificationStorage storage;

    CandidateProfile me;
    String snapshotKey;
    String documentKey;

    @BeforeEach
    void setUp() throws Exception {
        me = fixtures.candidate(EMAIL);
        User user = me.getUser();
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        users.save(user);
        Organization organization = fixtures.organization("Privacy Verification Org");
        User reviewer = fixtures.staff(organization, "reviewer@privacy.test", Role.RECRUITER);

        CandidateClaim claim = verification.claim(me, ClaimType.SKILL, "Scala");
        VerificationSource github = verification.source(me, VerificationSourceKind.GITHUB, "private-dev", true);
        github.setAccessTokenEnc("v1:encrypted-token-placeholder");
        sources.save(github);
        documentKey = storage.store("%PDF-1.7 private".getBytes(StandardCharsets.US_ASCII));
        VerificationSource document = verification.source(me, VerificationSourceKind.DOCUMENT, "cv.pdf", false);
        document.setFilePath(documentKey);
        sources.save(document);
        snapshotKey = storage.store("snapshot text".getBytes(StandardCharsets.UTF_8));
        EvidenceItem item = new EvidenceItem();
        item.setSourceId(github.getId());
        item.setCandidateId(me.getId());
        item.setItemKey("repo-1");
        item.setKind(EvidenceItemKind.REPOSITORY);
        item.setUrl("https://github.com/private-dev/x");
        item.setRetrievedAt(UtcTime.now());
        item.setContentHash("sha256:" + "3".repeat(64));
        item.setSnapshotPath(snapshotKey);
        evidence.save(item);
        ClaimVerdict verdict = verification.verdict(github, claim, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);
        ClaimVerdictReview review = new ClaimVerdictReview();
        review.setVerdictId(verdict.getId());
        review.setOrganizationId(organization.getId());
        review.setDecision(ReviewDecision.CONFIRM);
        review.setReviewerUserId(reviewer.getId());
        reviews.save(review);
        VerificationReferenceRequest reference = new VerificationReferenceRequest();
        reference.setCandidateId(me.getId());
        reference.setRefereeName("Ref Eree");
        reference.setRefereeEmail("ref@employer.test");
        reference.setRelationship(ReferenceRelationship.PEER);
        reference.setClaimRefsJson("[]");
        reference.setTokenHash("d".repeat(64));
        reference.setStatus(ReferenceStatus.SENT);
        reference.setExpiresAt(UtcTime.now().plusDays(14));
        reference.setConsentedAt(UtcTime.now());
        references.save(reference);
    }

    @Test
    void theExportListsVerificationDataWithoutSecrets() throws Exception {
        String body = mvc.perform(get("/api/v1/candidates/me/export").with(candidate(EMAIL)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("encrypted-token-placeholder"), "tokens are never exported");
        assertFalse(body.contains("d".repeat(64)), "reference token hashes are never exported");
        assertFalse(body.contains(documentKey), "storage paths are never exported");
        JsonNode verificationData = mapper.readTree(body).path("data").path("verification");
        assertEquals(2, verificationData.path("sources").size());
        assertTrue(verificationData.path("sources").path(0).path("accessTokenStored").asBoolean());
        assertEquals(1, verificationData.path("evidence").size());
        assertEquals("sha256:" + "3".repeat(64), verificationData.path("evidence").path(0).path("contentHash").asText());
        assertEquals(1, verificationData.path("verdicts").size());
        assertEquals("VERIFIED", verificationData.path("verdicts").path(0).path("verdict").asText());
        assertEquals(1, verificationData.path("references").size());
        assertEquals("ref@employer.test", verificationData.path("references").path(0).path("refereeEmail").asText());
        assertTrue(verificationData.has("runs"));
    }

    @Test
    void accountDeletionRemovesVerificationRowsAndFiles() throws Exception {
        assertTrue(storage.exists(snapshotKey));
        assertTrue(storage.exists(documentKey));
        mvc.perform(delete("/api/v1/candidates/me").with(candidate(EMAIL)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isOk());
        assertTrue(sources.findAllByCandidateIdOrderByIdAsc(me.getId()).isEmpty());
        assertTrue(evidence.findAllByCandidateIdOrderByIdAsc(me.getId()).isEmpty());
        assertTrue(verdicts.findAllByCandidateIdOrderByIdAsc(me.getId()).isEmpty());
        assertEquals(0, reviews.count());
        assertTrue(references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).isEmpty());
        assertFalse(storage.exists(snapshotKey), "snapshots are deleted after the commit");
        assertFalse(storage.exists(documentKey), "uploaded documents are deleted after the commit");
    }
}
