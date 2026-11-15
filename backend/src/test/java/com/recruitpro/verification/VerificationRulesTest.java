package com.recruitpro.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.client.AgentServiceClient;
import com.recruitpro.verification.client.AgentServiceException;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.dto.DomainTrustView;
import com.recruitpro.verification.service.DomainTrusts;
import com.recruitpro.verification.service.VerificationResultWriter;
import com.recruitpro.verification.service.VerificationSettingsService;
import com.recruitpro.verification.service.VerificationStaffSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules: subject references, the ownership cap, the tier rule, evidence hashing and agent error mapping. */
class VerificationRulesTest {

    @Test
    void subjectReferencesRoundTrip() {
        assertEquals("fact:12", ClaimSubjectRef.of(SubjectType.RESUME_FACT, 12L));
        assertEquals("claim:7", ClaimSubjectRef.of(SubjectType.CANDIDATE_CLAIM, 7L));
        assertEquals(new ClaimSubjectRef(SubjectType.RESUME_FACT, 12L), ClaimSubjectRef.parse("fact:12"));
        assertEquals(new ClaimSubjectRef(SubjectType.CANDIDATE_CLAIM, 7L), ClaimSubjectRef.parse(" claim:7 "));
        assertNull(ClaimSubjectRef.parse("ws:1:r2"));
        assertNull(ClaimSubjectRef.parse("fact:"));
        assertNull(ClaimSubjectRef.parse("fact:-1"));
        assertNull(ClaimSubjectRef.parse(null));
    }

    @Test
    void theOwnershipCapLimitsOnlyVerified() {
        assertEquals(VerdictValue.PARTLY_VERIFIED, VerdictValue.VERIFIED.capped(false));
        assertEquals(VerdictValue.VERIFIED, VerdictValue.VERIFIED.capped(true));
        assertEquals(VerdictValue.CONTRADICTED, VerdictValue.CONTRADICTED.capped(false));
        assertEquals(VerdictValue.NOT_FOUND, VerdictValue.NOT_FOUND.capped(false));
        assertNull(VerdictValue.parse("maybe"));
        assertEquals(VerdictValue.NOT_CHECKABLE, VerdictValue.parse("not_checkable"));
    }

    @Test
    void theTierRuleNeedsEveryCondition() {
        VerificationSettingsService.Effective visible = new VerificationSettingsService.Effective(VerificationMode.VISIBLE,
                Map.of("GITHUB", true, "DOCUMENT", false));
        VerificationSettingsService.Effective shadow = new VerificationSettingsService.Effective(VerificationMode.SHADOW,
                Map.of("GITHUB", true));
        ClaimVerdictReview confirm = review(ReviewDecision.CONFIRM);
        assertTrue(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                confirm, visible, "GITHUB"));
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                confirm, visible, "GITHUB"), "only VERIFIED");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, false, CandidateVerdictStatus.SHARED, true),
                confirm, visible, "GITHUB"), "ownership proven");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.HIDDEN, true),
                confirm, visible, "GITHUB"), "shared by the candidate");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, false),
                confirm, visible, "GITHUB"), "current");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                confirm, shadow, "GITHUB"), "VISIBLE mode");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                confirm, visible, "DOCUMENT"), "connector enabled");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                review(ReviewDecision.OVERRULE), visible, "GITHUB"), "latest review CONFIRM");
        assertFalse(VerificationStaffSupport.lifts(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true),
                null, visible, "GITHUB"), "reviewed at all");
    }

    @Test
    void domainTrustCountsOnlyForTheOrganisationThatListsTheDomain() {
        VerificationSettingsService.Effective trusting = new VerificationSettingsService.Effective(VerificationMode.VISIBLE,
                Map.of("REFERENCE", true), List.of("acme.example"), List.of());
        VerificationSettingsService.Effective badgesOnly = new VerificationSettingsService.Effective(VerificationMode.VISIBLE,
                Map.of("REFERENCE", true), List.of(), List.of("acme.example"));
        VerificationSettingsService.Effective plain = new VerificationSettingsService.Effective(VerificationMode.VISIBLE,
                Map.of("REFERENCE", true));
        ClaimVerdict capped = verdict(VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED, true);
        capped.setDomainTrustJson(VerificationFixtures.domainTrustJson("EMPLOYER_DOMAIN", "eu.acme.example", "WEAK", "VERIFIED"));
        ClaimVerdict partly = verdict(VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED, true);
        partly.setDomainTrustJson(VerificationFixtures.domainTrustJson("EMPLOYER_DOMAIN", "acme.example", "WEAK", null));
        ClaimVerdictReview confirm = review(ReviewDecision.CONFIRM);

        assertEquals(VerdictValue.VERIFIED, DomainTrusts.effectiveVerdict(capped, trusting), "a subdomain of a listed domain");
        assertTrue(VerificationStaffSupport.lifts(capped, confirm, trusting, "REFERENCE"));
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(capped, plain), "other organisations");
        assertFalse(VerificationStaffSupport.lifts(capped, confirm, plain, "REFERENCE"));
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(capped, badgesOnly), "the list of the other check");
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(partly, trusting), "not capped by the domain");
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(capped, null));
        ClaimVerdict notOwned = verdict(VerdictValue.PARTLY_VERIFIED, false, CandidateVerdictStatus.SHARED, true);
        notOwned.setDomainTrustJson(capped.getDomainTrustJson());
        assertFalse(VerificationStaffSupport.lifts(notOwned, confirm, trusting, "REFERENCE"), "ownership still required");
        ClaimVerdict broken = verdict(VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED, true);
        broken.setDomainTrustJson("{not json");
        assertEquals(VerdictValue.PARTLY_VERIFIED, DomainTrusts.effectiveVerdict(broken, trusting));

        DomainTrustView blind = DomainTrusts.view(capped, trusting, false);
        assertEquals("TRUSTED", blind.status());
        assertEquals("ORGANIZATION", blind.trustedBy());
        assertEquals("WEAK", blind.agentStatus());
        assertTrue(blind.liftedByOrganization());
        assertNull(blind.domain(), "blind-first: the domain is withheld until reveal");
        assertEquals("eu.acme.example", DomainTrusts.view(capped, trusting, true).domain());
        DomainTrustView other = DomainTrusts.view(capped, plain, true);
        assertEquals("WEAK", other.status());
        assertNull(other.trustedBy());
        assertFalse(other.liftedByOrganization());
        ClaimVerdict agentTrusted = verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true);
        agentTrusted.setDomainTrustJson(VerificationFixtures.domainTrustJson("BADGE_ISSUER", "credly.com", "TRUSTED", null));
        assertEquals("AGENT", DomainTrusts.view(agentTrusted, plain, false).trustedBy());
        assertNull(DomainTrusts.view(verdict(VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED, true), plain, true));

        assertEquals("acme.example", DomainTrusts.normalise(" https://www.ACME.example/about "));
        assertEquals("acme.example", DomainTrusts.normalise("acme.example.:443"));
        assertTrue(DomainTrusts.within("eu.acme.example", "acme.example"));
        assertFalse(DomainTrusts.within("notacme.example", "acme.example"));
        assertFalse(DomainTrusts.within("acme.example", ""));
    }

    @Test
    void evidenceHashesMustMatchTheSnapshot() {
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", VerificationResultWriter.sha256(""));
        AgentModels.EvidenceItem good = VerificationFixtures.item("page-1", "PAGE", "https://x.test", "Hello world");
        AgentModels.EvidenceItem bad = new AgentModels.EvidenceItem("page-2", "PAGE", null, "", "2026-11-15T10:00:00Z",
                good.contentHash(), "", "Hello world!", null);
        AgentModels.EvidenceItem unknownKind = VerificationFixtures.item("x-1", "TWEET", null, "text");
        assertNull(invokeValidate(List.of(good)));
        assertEquals("EVIDENCE_HASH_MISMATCH", invokeValidate(List.of(good, bad)));
        assertEquals("INVALID_EVIDENCE", invokeValidate(List.of(unknownKind)));
        assertEquals("INVALID_EVIDENCE", invokeValidate(List.of(good, good)), "duplicate keys");
        String upper = "SHA256:" + good.contentHash().substring(7).toUpperCase();
        assertNull(invokeValidate(List.of(new AgentModels.EvidenceItem("k", "PAGE", null, "", "2026-11-15T10:00:00Z", upper, "",
                "Hello world", null))), "hash comparison ignores case");
    }

    @Test
    void agentErrorsCarryDetailCode() {
        AgentServiceClient client = new AgentServiceClient(RestClient.builder(), new ObjectMapper(), "http://127.0.0.1:9", "t", 1000, 1000);
        AgentServiceException coded = translate(client, HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"detail\":{\"code\":\"URL_BLOCKED\",\"message\":\"private address\"}}");
        assertEquals("URL_BLOCKED", coded.code());
        assertEquals(422, coded.status());
        assertFalse(coded.isTransient());
        AgentServiceException upstream = translate(client, HttpStatus.BAD_GATEWAY, "{\"detail\":{\"code\":\"RATE_LIMITED\",\"message\":\"x\"}}");
        assertEquals("RATE_LIMITED", upstream.code());
        assertFalse(upstream.isTransient(), "a coded failure is recorded, not retried");
        AgentServiceException proxy = translate(client, HttpStatus.SERVICE_UNAVAILABLE, "<html>down</html>");
        assertNull(proxy.code());
        assertTrue(proxy.isTransient());
        AgentServiceException auth = translate(client, HttpStatus.UNAUTHORIZED, "{\"detail\":\"invalid token\"}");
        assertNull(auth.code());
        assertFalse(auth.isTransient());
        AgentServiceException weird = translate(client, HttpStatus.BAD_GATEWAY, "{\"detail\":{\"code\":\"<script>\"}}");
        assertEquals("AGENT_ERROR", weird.code());
    }

    private static AgentServiceException translate(AgentServiceClient client, HttpStatus status, String body) {
        RestClientResponseException ex = new RestClientResponseException("agent error", status, status.getReasonPhrase(),
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return client.translate(ex);
    }

    private static String invokeValidate(List<AgentModels.EvidenceItem> items) {
        return VerificationResultWriter.validateEvidence(items);
    }

    private static ClaimVerdict verdict(VerdictValue value, boolean ownership, CandidateVerdictStatus status, boolean current) {
        ClaimVerdict verdict = new ClaimVerdict();
        verdict.setVerdict(value);
        verdict.setOwnershipProven(ownership);
        verdict.setCandidateStatus(status);
        verdict.setCurrent(current);
        return verdict;
    }

    private static ClaimVerdictReview review(ReviewDecision decision) {
        ClaimVerdictReview review = new ClaimVerdictReview();
        review.setDecision(decision);
        return review;
    }
}
