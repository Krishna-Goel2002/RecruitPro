package com.recruitpro.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.service.ClaimEvidence;
import com.recruitpro.evidence.service.EvidenceContributor;
import com.recruitpro.evidence.service.MatchRequestBuilder;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1: candidate edits never raise verified coverage. The request builder sends the extracted resume fact at
 * RESUME_STATED and the candidate's correction as a separate SELF_CLAIMED claim; rejected claims and contact facts
 * are not sent.
 */
class MatchRequestBuilderTest {

    private static final Long ORG = 10L;
    private static final Long OTHER_ORG = 20L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MatchRequestBuilder builder = new MatchRequestBuilder(mapper);

    @Test
    void candidateCorrectionBecomesASelfClaimedFactAndTheExtractedFactStaysResumeStated() {
        ResumeAnalysisFact java = fact(41L, ResumeFactType.SKILL, "Java");
        java.setReviewStatus(ResumeFactReviewStatus.CORRECTED);
        java.setReviewedValue("Java, Kotlin and Scala");
        java.setAttributesJson("{\"skillId\":\"java\",\"domain\":\"SOFTWARE\"}");
        CandidateClaim correction = claim(7L, ClaimType.SKILL, "Java, Kotlin and Scala", EvidenceTier.SELF_CLAIMED);
        correction.setSourceFactId(41L);

        AiMatchRequest request = builder.build(List.of(requirement(11L, "Kotlin", true, true)), List.of(java),
                List.of(here(correction)), List.of(), null);

        assertEquals(2, request.facts().size());
        AiMatchRequest.Fact resumeFact = request.facts().get(0);
        assertEquals("fact:41", resumeFact.id());
        assertEquals("Java", resumeFact.value(), "the extracted value is sent, never the correction");
        assertEquals("RESUME_STATED", resumeFact.tier());
        assertEquals("java", resumeFact.attributes().path("skillId").asText());
        AiMatchRequest.Fact claimFact = request.facts().get(1);
        assertEquals("claim:7", claimFact.id());
        assertEquals("Java, Kotlin and Scala", claimFact.value());
        assertEquals("SELF_CLAIMED", claimFact.tier());
        assertEquals("SKILL", claimFact.type());
        assertNull(claimFact.evidence(), "evidence URLs may identify the candidate and are not sent");
    }

    @Test
    void corroboratedClaimsKeepTheirTierAndRejectedClaimsAreNotSent() {
        CandidateClaim corroborated = claim(1L, ClaimType.CERTIFICATION, "AWS Solutions Architect", EvidenceTier.CORROBORATED);
        corroborated.setVerifiedAt(LocalDateTime.parse("2026-11-01T10:00:00"));
        CandidateClaim rejected = claim(2L, ClaimType.SKILL, "Rust", EvidenceTier.SELF_CLAIMED);
        rejected.setVerifiedAt(LocalDateTime.parse("2026-11-01T10:00:00"));
        CandidateClaim pending = claim(3L, ClaimType.OTHER, "Willing to relocate", EvidenceTier.SELF_CLAIMED);

        AiMatchRequest request = builder.build(List.of(requirement(11L, "AWS", false, false)), List.of(),
                List.of(here(corroborated), here(rejected), here(pending)), List.of(), null);

        assertEquals(List.of("claim:1", "claim:3"), request.facts().stream().map(AiMatchRequest.Fact::id).toList());
        assertEquals("CORROBORATED", request.facts().get(0).tier());
        assertEquals("OTHER", request.facts().get(1).type());
        assertTrue(request.facts().stream().noneMatch(f -> "RESUME_STATED".equals(f.tier())),
                "claims never become resume evidence");
    }

    @Test
    void anotherOrganisationsVerificationNeitherRaisesNorRemovesAClaim() {
        CandidateClaim corroboratedElsewhere = claim(1L, ClaimType.CERTIFICATION, "AWS Solutions Architect", EvidenceTier.CORROBORATED);
        corroboratedElsewhere.setVerifiedAt(LocalDateTime.parse("2026-11-01T10:00:00"));
        CandidateClaim rejectedElsewhere = claim(2L, ClaimType.SKILL, "Rust", EvidenceTier.SELF_CLAIMED);
        rejectedElsewhere.setVerifiedAt(LocalDateTime.parse("2026-11-01T10:00:00"));

        AiMatchRequest request = builder.build(List.of(requirement(11L, "AWS", false, false)), List.of(),
                List.of(ClaimEvidence.forOrganization(corroboratedElsewhere, OTHER_ORG, ORG),
                        ClaimEvidence.forOrganization(rejectedElsewhere, OTHER_ORG, ORG)), List.of(), null);

        assertEquals(List.of("claim:1", "claim:2"), request.facts().stream().map(AiMatchRequest.Fact::id).toList());
        assertTrue(request.facts().stream().allMatch(f -> "SELF_CLAIMED".equals(f.tier())));
    }

    @Test
    void contactFactsAreNeverSentAndContributedEvidenceKeepsItsTier() {
        List<ResumeAnalysisFact> facts = List.of(fact(1L, ResumeFactType.EMAIL, "a@b.test"),
                fact(2L, ResumeFactType.PHONE, "+91 98765 43210"), fact(3L, ResumeFactType.LINK, "https://github.com/someone"),
                fact(4L, ResumeFactType.EXPERIENCE, "Accountant, Kalinga Steel Ltd"));
        AiMatchRequest.Fact workSample = EvidenceContributor.fact("ws:4:r11", "SKILL", "Tally", "Work sample rubric score 3/4",
                "work sample", EvidenceTier.DEMONSTRATED);

        AiMatchRequest request = builder.build(List.of(requirement(11L, "Tally", true, true)), facts, List.of(),
                List.of(workSample), null);

        assertEquals(List.of("fact:4", "ws:4:r11"), request.facts().stream().map(AiMatchRequest.Fact::id).toList());
        assertEquals("DEMONSTRATED", request.facts().get(1).tier());
    }

    @Test
    void requirementsV2FieldsAndTimelineAreSentWithinServiceLimits() {
        JobRequirement requirement = requirement(11L, "SAP FICO", true, false);
        requirement.setMinimumYears(70);
        requirement.setProficiency(Proficiency.ADVANCED);
        List<String> alternatives = new ArrayList<>();
        for (int i = 0; i < 12; i++) alternatives.add("Tool " + i);
        alternatives.add(" ");
        requirement.setAlternatives(alternatives);
        ObjectNode timeline = mapper.createObjectNode().put("totalMonths", 95);

        AiMatchRequest request = builder.build(List.of(requirement), List.of(), List.of(), List.of(), timeline);

        AiMatchRequest.Requirement sent = request.requirements().get(0);
        assertEquals(11L, sent.id());
        assertEquals("SKILL", sent.type());
        assertTrue(sent.mandatory());
        assertFalse(sent.knockout());
        assertEquals(50, sent.minimumYears());
        assertEquals(10, sent.alternatives().size());
        assertEquals("ADVANCED", sent.proficiency());
        assertEquals(95, request.timeline().path("totalMonths").asInt());
    }

    private static JobRequirement requirement(Long id, String description, boolean mandatory, boolean knockout) {
        JobRequirement requirement = new JobRequirement();
        requirement.setId(id);
        requirement.setType(RequirementType.SKILL);
        requirement.setDescription(description);
        requirement.setMandatory(mandatory);
        requirement.setKnockout(knockout);
        return requirement;
    }

    private static ResumeAnalysisFact fact(Long id, ResumeFactType type, String value) {
        ResumeAnalysisFact fact = new ResumeAnalysisFact();
        fact.setId(id);
        fact.setType(type);
        fact.setExtractedValue(value);
        fact.setNormalizedValue(value.toLowerCase());
        fact.setEvidence(value);
        fact.setSourceSection("skills");
        fact.setEvidenceTier(EvidenceTier.RESUME_STATED);
        return fact;
    }

    /** The claim as evidence for a job of the organisation that verified it (or for any job when unverified). */
    private static ClaimEvidence here(CandidateClaim claim) {
        return ClaimEvidence.forOrganization(claim, ORG, ORG);
    }

    private static CandidateClaim claim(Long id, ClaimType type, String value, EvidenceTier tier) {
        CandidateClaim claim = new CandidateClaim();
        claim.setId(id);
        claim.setClaimType(type);
        claim.setClaimValue(value);
        claim.setEvidenceUrl("https://example.test/proof");
        claim.setTier(tier);
        return claim;
    }
}
