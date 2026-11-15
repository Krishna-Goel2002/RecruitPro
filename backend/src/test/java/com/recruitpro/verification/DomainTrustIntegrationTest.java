package com.recruitpro.verification;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.VerificationFixtures;
import com.recruitpro.user.entity.Role;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.OrganizationVerificationSettings;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trust fixes (V26): an organisation's trusted employer domains and badge issuers. A verdict the agent capped at
 * PARTLY_VERIFIED only because it could not establish the issuer / employer domain counts as VERIFIED for an
 * organisation that lists that domain, and for no other organisation; the staff view says who trusted the domain.
 */
class DomainTrustIntegrationTest extends VerificationIntegrationTestBase {

    private static final String SETTINGS = "/api/v1/admin/verification/settings";
    private static final String VIEW = "/api/v1/hr/applications/{id}/verification";
    private static final String REVIEW = "/api/v1/hr/verdicts/{id}/review";

    @Autowired ScreeningFixtures screening;
    @Autowired ApplicationRepository applications;
    @Autowired ClaimVerdictRepository verdicts;
    @Autowired OrganizationVerificationSettingsRepository settings;
    @Autowired MatchingService matchingService;

    Organization trustingOrg;
    Organization otherOrg;
    Application application;
    Application otherApplication;
    ClaimVerdict capped;

    @BeforeEach
    void setUp() {
        trustingOrg = fixtures.organization("Trusting Org");
        fixtures.staff(trustingOrg, "admin@trusting.test", Role.ADMIN);
        HRProfile owner = fixtures.hrMember(trustingOrg, "hr@trusting.test", Role.HR_MANAGER);
        otherOrg = fixtures.organization("Other Org");
        fixtures.staff(otherOrg, "admin@other.test", Role.ADMIN);
        HRProfile otherOwner = fixtures.hrMember(otherOrg, "hr@other.test", Role.HR_MANAGER);

        CandidateProfile candidate = fixtures.candidate("badges@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        screening.analysis(resume, ResumeAnalysisStatus.COMPLETED); // matching needs a finished analysis
        CandidateClaim cert = verification.claim(candidate, ClaimType.CERTIFICATION, "Data Analysis with Python");
        Job job = fixtures.job(owner, "Data Analyst", JobStatus.PUBLISHED);
        screening.requirement(job, RequirementType.SKILL, "Python", true, false);
        Job otherJob = fixtures.job(otherOwner, "Analyst", JobStatus.PUBLISHED);
        screening.requirement(otherJob, RequirementType.SKILL, "Python", true, false);
        application = screening.application(candidate, job, resume, UtcTime.now());
        otherApplication = screening.application(candidate, otherJob, resume, UtcTime.now());

        VerificationSource badge = verification.source(candidate, VerificationSourceKind.OPEN_BADGE,
                "https://badges.issuer.example/assertions/1", true);
        ClaimVerdict verdict = verification.verdict(badge, cert, VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED);
        verdict.setVerifier("badge");
        verdict.setDomainTrustJson(VerificationFixtures.domainTrustJson("BADGE_ISSUER", "badges.issuer.example", "WEAK", "VERIFIED"));
        capped = verdicts.save(verdict);
        verification.mode(trustingOrg.getId(), VerificationMode.VISIBLE);
        verification.mode(otherOrg.getId(), VerificationMode.VISIBLE);
    }

    @Test
    void trustedDomainListsAreValidatedNormalisedAndKept() throws Exception {
        mvc.perform(get(SETTINGS).with(staff("admin@trusting.test", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustedEmployerDomains", hasSize(0)))
                .andExpect(jsonPath("$.data.trustedBadgeIssuers", hasSize(0)));
        for (String bad : new String[] {"\"not a domain\"", "\"localhost\"", "\"co.uk\"", "\"\""}) {
            mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mode\":\"VISIBLE\",\"trustedEmployerDomains\":[" + bad + "]}")).andExpect(status().isBadRequest());
        }
        mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VISIBLE\",\"trustedEmployerDomains\":[\"https://www.Acme.example/about\",\"acme.example\"],"
                                + "\"trustedBadgeIssuers\":[\"credly.com\",\"issuer.example\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustedEmployerDomains", contains("acme.example")))
                .andExpect(jsonPath("$.data.trustedBadgeIssuers", contains("credly.com", "issuer.example")));
        // Lists left out keep their value; an empty list clears.
        mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VISIBLE\",\"trustedBadgeIssuers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trustedEmployerDomains", contains("acme.example")))
                .andExpect(jsonPath("$.data.trustedBadgeIssuers", hasSize(0)));
        OrganizationVerificationSettings row = settings.findById(trustingOrg.getId()).orElseThrow();
        assertEquals("[\"acme.example\"]", row.getTrustedEmployerDomainsJson());
        assertEquals("[]", row.getTrustedBadgeIssuersJson());
        mvc.perform(put(SETTINGS).with(staff("hr@trusting.test", "HR_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\",\"trustedBadgeIssuers\":[\"evil.example\"]}")).andExpect(status().isForbidden());
    }

    @Test
    void anOrganisationThatTrustsTheIssuerSeesVerifiedAndOnlyItCanLiftIt() throws Exception {
        mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\",\"trustedBadgeIssuers\":[\"issuer.example\"]}")).andExpect(status().isOk());

        mvc.perform(get(VIEW, application.getId()).with(staff("hr@trusting.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].verdict", is("VERIFIED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.check", is("BADGE_ISSUER")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.status", is("TRUSTED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.trustedBy", is("ORGANIZATION")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.agentStatus", is("WEAK")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.liftedByOrganization", is(true)))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.domain", nullValue()));
        mvc.perform(get(VIEW, otherApplication.getId()).with(staff("hr@other.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].verdict", is("PARTLY_VERIFIED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.status", is("WEAK")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.trustedBy", nullValue()))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.liftedByOrganization", is(false)));

        // Both organisations confirm; only the trusting one gets the CORROBORATED lift and the matching fact.
        for (String reviewer : new String[] {"hr@trusting.test", "hr@other.test"}) {
            mvc.perform(post(REVIEW, capped.getId()).with(staff(reviewer, "HR_MANAGER"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")).andExpect(status().isOk());
        }
        mvc.perform(get(VIEW, application.getId()).with(staff("hr@trusting.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("CORROBORATED")));
        mvc.perform(get(VIEW, otherApplication.getId()).with(staff("hr@other.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].tier", is("SELF_CLAIMED")));
        AiMatchRequest.Fact lifted = vf(matchingService.prepare(application.getId()));
        assertNotNull(lifted);
        assertEquals("Verified by badge; domain trusted by the organisation; confirmed by a recruiter", lifted.evidence());
        assertNull(vf(matchingService.prepare(otherApplication.getId())), "no lift for an organisation that does not trust it");
        assertEquals(VerdictValue.PARTLY_VERIFIED, verdicts.findById(capped.getId()).orElseThrow().getVerdict(),
                "the stored verdict never changes");

        // After reveal the domain is shown; removing the issuer from the list removes the lift.
        Application revealed = applications.findById(application.getId()).orElseThrow();
        revealed.setIdentityRevealedAt(UtcTime.now());
        applications.save(revealed);
        mvc.perform(get(VIEW, application.getId()).with(staff("hr@trusting.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.domain", is("badges.issuer.example")));
        mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\",\"trustedBadgeIssuers\":[]}")).andExpect(status().isOk());
        assertNull(vf(matchingService.prepare(application.getId())));
    }

    @Test
    void anEmployerDomainListDoesNotTrustBadgeIssuers() throws Exception {
        mvc.perform(put(SETTINGS).with(staff("admin@trusting.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\",\"trustedEmployerDomains\":[\"issuer.example\"]}")).andExpect(status().isOk());
        mvc.perform(get(VIEW, application.getId()).with(staff("hr@trusting.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].verdict", is("PARTLY_VERIFIED")))
                .andExpect(jsonPath("$.data.claims[0].verdicts[0].domainTrust.trustedBy", nullValue()));
        assertTrue(settings.findById(trustingOrg.getId()).isPresent());
    }

    private static AiMatchRequest.Fact vf(PreparedMatch prepared) {
        assertNotNull(prepared);
        return prepared.request().facts().stream().filter(f -> f.id().startsWith("vf:")).findFirst().orElse(null);
    }
}
