package com.recruitpro.verification;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.service.VerificationMaintenance;
import com.recruitpro.verification.service.VerifyReferenceJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** References: request with consent, public referee page (answer once, decline, expiry 410) and VERIFY_REFERENCE. */
class ReferenceIntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/candidates/me/verification/references";
    private static final String PUBLIC = "/api/v1/public/references/{token}";
    private static final Pattern LINK = Pattern.compile("http://localhost:5173/reference/([A-Za-z0-9_-]{43})");

    @Autowired VerificationReferenceRequestRepository references;
    @Autowired NotificationRepository notifications;
    @Autowired BackgroundJobRepository jobs;
    @Autowired VerifyReferenceJobHandler handler;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired EvidenceItemRepository evidence;
    @Autowired VerificationMaintenance maintenance;

    CandidateProfile me;
    CandidateClaim role;
    CandidateClaim skill;

    @BeforeEach
    void setUp() {
        me = fixtures.candidate("refs@cand.test");
        role = verification.claim(me, ClaimType.EXPERIENCE, "Backend engineer at Acme, 2021-2024");
        skill = verification.claim(me, ClaimType.SKILL, "Kotlin");
    }

    @Test
    void theRefereeAnswersOnceAndTheAnswerIsVerified() throws Exception {
        String token = request("rita@acme.example");
        assertEquals(1, references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).size());
        VerificationReferenceRequest stored = references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).get(0);
        assertFalse(stored.getTokenHash().contains(token), "only the SHA-256 of the token is stored");
        assertTrue(stored.getExpiresAt().isAfter(UtcTime.now().plusDays(13)));

        mvc.perform(get(PUBLIC, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateFirstName", is("Test")))
                .andExpect(jsonPath("$.data.relationship", is("MANAGER")))
                .andExpect(jsonPath("$.data.employerName", is("Acme")))
                .andExpect(jsonPath("$.data.claims", hasSize(2)))
                .andExpect(jsonPath("$.data.claims[0].id", is("claim:" + role.getId())))
                .andExpect(jsonPath("$.data.claims[0].claimType", is("EXPERIENCE")))
                .andExpect(jsonPath("$.data.expiresAt").exists());
        mvc.perform(get(PUBLIC, "unknown-token")).andExpect(status().isNotFound());

        mvc.perform(post(PUBLIC, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"claimId\":\"claim:" + role.getId() + "\",\"answer\":\"CONFIRM\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PUBLIC, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"claimId\":\"claim:424242\",\"answer\":\"CONFIRM\"}],\"confirmIdentity\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PUBLIC, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"claimId\":\"claim:" + role.getId() + "\",\"answer\":\"CONFIRM\",\"comment\":\"Led our payments team\"},"
                                + "{\"claimId\":\"claim:" + skill.getId() + "\",\"answer\":\"DISAGREE\",\"comment\":\"Mostly Java\"}],"
                                + "\"refereeComment\":\"Great colleague\",\"confirmIdentity\":true}"))
                .andExpect(status().isOk());
        VerificationReferenceRequest answered = references.findById(stored.getId()).orElseThrow();
        assertEquals(ReferenceStatus.ANSWERED, answered.getStatus());
        assertNotNull(answered.getAnsweredAt());
        assertEquals(1, jobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.VERIFY_REFERENCE, stored.getId()).size());
        // Answered once: the link is gone.
        mvc.perform(get(PUBLIC, token)).andExpect(status().isGone());
        mvc.perform(post(PUBLIC, token).contentType(MediaType.APPLICATION_JSON).content("{\"decline\":true}"))
                .andExpect(status().isGone());

        String referenceSnapshot = "Referee answer: CONFIRM";
        // V26: the candidate's proven websites travel with the request (they can never vouch for an employer).
        verification.source(me, VerificationSourceKind.WEBSITE, "https://www.Portfolio.example/about", true);
        verification.source(me, VerificationSourceKind.WEBSITE, "https://unproven.example/", false);
        String trust = VerificationFixtures.domainTrustJson("EMPLOYER_DOMAIN", "acme.example", "TRUSTED", null);
        when(agentClient.verifyReference(any())).thenReturn(VerificationFixtures.response("reference",
                List.of(VerificationFixtures.item("reference-1", "REFERENCE_RESPONSE", null, referenceSnapshot)),
                List.of(VerificationFixtures.withDomainTrust(VerificationFixtures.agentVerdict("claim:" + role.getId(),
                                "EXPERIENCE", "x", "VERIFIED", true, "reference-1"), mapper.readTree(trust)),
                        VerificationFixtures.agentVerdict("claim:" + skill.getId(), "SKILL", "x", "CONTRADICTED", false, "reference-1"))));
        handler.handle(stored.getId());
        ArgumentCaptor<AgentModels.ReferenceVerifyRequest> sent = ArgumentCaptor.forClass(AgentModels.ReferenceVerifyRequest.class);
        verify(agentClient).verifyReference(sent.capture());
        assertEquals("acme.example", sent.getValue().referee().emailDomain());
        assertEquals("acme.example", sent.getValue().referee().employerDomain());
        assertEquals("MANAGER", sent.getValue().referee().relationship());
        assertEquals("Acme", sent.getValue().referee().employerName());
        assertEquals(List.of("portfolio.example"), sent.getValue().candidateDomains());
        assertEquals("Test Candidate", sent.getValue().holderName());
        assertEquals(2, sent.getValue().answers().size());

        List<ClaimVerdict> stored2 = verdicts.findAllByReferenceIdOrderByIdAsc(stored.getId());
        assertEquals(2, stored2.size());
        assertEquals(VerdictValue.VERIFIED, stored2.get(0).getVerdict());
        assertTrue(stored2.get(0).isOwnershipProven(), "an independent referee anchors the claim");
        assertEquals("Backend engineer at Acme, 2021-2024", stored2.get(0).getClaimValue());
        assertEquals(CandidateVerdictStatus.AWAITING_CANDIDATE, stored2.get(0).getCandidateStatus());
        assertEquals("acme.example", mapper.readTree(stored2.get(0).getDomainTrustJson()).path("domain").asText());
        assertEquals(VerdictValue.CONTRADICTED, stored2.get(1).getVerdict());
        assertEquals(null, stored2.get(1).getDomainTrustJson());
        assertEquals(1, evidence.findAllByCandidateIdOrderByIdAsc(me.getId()).size());
        assertNotNull(references.findById(stored.getId()).orElseThrow().getVerifiedAt());
        assertFalse(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("refs@cand.test",
                NotificationTemplate.REFERENCE_ANSWERED).isEmpty());
        // Running the job again does nothing.
        handler.handle(stored.getId());
        assertEquals(2, verdicts.findAllByReferenceIdOrderByIdAsc(stored.getId()).size());

        mvc.perform(get(BASE).with(candidate("refs@cand.test")))
                .andExpect(jsonPath("$.data[0].status", is("ANSWERED")))
                .andExpect(jsonPath("$.data[0].verdictCount", is(2)));
    }

    @Test
    void declineExpiryCancelAndLimits() throws Exception {
        String declined = request("decline@peer.example");
        mvc.perform(post(PUBLIC, declined).contentType(MediaType.APPLICATION_JSON).content("{\"decline\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get(PUBLIC, declined)).andExpect(status().isGone());
        assertFalse(notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc("refs@cand.test",
                NotificationTemplate.REFERENCE_ANSWERED).isEmpty(), "the candidate learns about the decline");

        String expiring = request("late@peer.example");
        VerificationReferenceRequest late = references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).get(0);
        late.setExpiresAt(UtcTime.now().minusMinutes(1));
        references.save(late);
        mvc.perform(get(PUBLIC, expiring)).andExpect(status().isGone());
        mvc.perform(post(PUBLIC, expiring).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[{\"claimId\":\"claim:" + role.getId() + "\",\"answer\":\"CONFIRM\"}],\"confirmIdentity\":true}"))
                .andExpect(status().isGone());
        assertEquals(1, maintenance.sweep());
        assertEquals(ReferenceStatus.EXPIRED, references.findById(late.getId()).orElseThrow().getStatus());

        // Max 5 open requests.
        for (int i = 0; i < 5; i++) {
            request("open" + i + "@peer.example");
        }
        mvc.perform(post(BASE).with(candidate("refs@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("sixth@peer.example", true)))
                .andExpect(status().isTooManyRequests());
        Long openId = references.findAllByCandidateIdOrderByCreatedAtDescIdDesc(me.getId()).get(0).getId();
        mvc.perform(post(BASE + "/{id}/cancel", openId).with(candidate("refs@cand.test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("CANCELLED")));
        mvc.perform(post(BASE + "/{id}/cancel", openId).with(candidate("refs@cand.test"))).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(candidate("refs@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content(body("sixth@peer.example", false))).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(candidate("refs@cand.test")).contentType(MediaType.APPLICATION_JSON)
                .content(body("refs@cand.test", true))).andExpect(status().isBadRequest());
    }

    /** Creates a request through the API and returns the token from the e-mailed link. */
    private String request(String refereeEmail) throws Exception {
        mvc.perform(post(BASE).with(candidate("refs@cand.test")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(refereeEmail, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("SENT")))
                .andExpect(jsonPath("$.data.claims", hasSize(2)));
        List<Notification> sent = notifications.findAllByRecipientEmailAndTemplateOrderByIdAsc(refereeEmail,
                NotificationTemplate.REFERENCE_REQUEST);
        assertEquals(1, sent.size(), "one e-mail, no inbox copy");
        // The consent text promises the referee sees the candidate's first name only.
        assertTrue(sent.get(0).getSubject().startsWith("Test asked you"), sent.get(0).getSubject());
        assertFalse(sent.get(0).getSubject().contains("Test Candidate"));
        assertFalse(sent.get(0).getBody().contains("Test Candidate"), "no last name in the referee e-mail");
        Matcher matcher = LINK.matcher(sent.get(0).getBody());
        assertTrue(matcher.find(), "the e-mail carries the referee link");
        return matcher.group(1);
    }

    private String body(String refereeEmail, boolean consent) {
        return "{\"refereeName\":\"Rita Referee\",\"refereeEmail\":\"" + refereeEmail + "\",\"relationship\":\"MANAGER\","
                + "\"employerName\":\"Acme\",\"employerDomain\":\"www.acme.example\",\"claims\":["
                + "{\"subjectType\":\"CANDIDATE_CLAIM\",\"subjectId\":" + role.getId() + "},"
                + "{\"subjectType\":\"CANDIDATE_CLAIM\",\"subjectId\":" + skill.getId() + "}],\"consent\":" + consent + "}";
    }
}
