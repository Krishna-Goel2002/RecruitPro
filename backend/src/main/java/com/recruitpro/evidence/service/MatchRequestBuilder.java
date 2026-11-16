package com.recruitpro.evidence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.RequirementType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the v3 match request (plan 6.1) from requirements v2, resume facts, candidate claims and contributed
 * evidence. Pure: no database or network access, so it is unit-tested directly.
 *
 * <p>C1 invariants: a resume fact is always sent with its <em>extracted</em> value at its stored tier
 * (RESUME_STATED); a candidate's correction is never folded into it but sent separately as the linked claim at
 * the claim's own tier (SELF_CLAIMED unless a recruiter of the job's organisation corroborated it, see
 * {@link ClaimEvidence}). Claims that organisation rejected are not sent. Contact facts (EMAIL, PHONE, LINK) are never
 * sent.</p>
 */
public final class MatchRequestBuilder {

    private final ObjectMapper objectMapper;

    public MatchRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiMatchRequest build(List<JobRequirement> requirements, List<ResumeAnalysisFact> resumeFacts,
                                List<ClaimEvidence> claims, List<AiMatchRequest.Fact> contributed, JsonNode timeline) {
        List<AiMatchRequest.Requirement> aiRequirements = new ArrayList<>();
        for (JobRequirement requirement : requirements == null ? List.<JobRequirement>of() : requirements) {
            if (aiRequirements.size() >= AiMatchRequest.MAX_REQUIREMENTS) {
                break;
            }
            aiRequirements.add(requirement(requirement));
        }

        List<AiMatchRequest.Fact> facts = new ArrayList<>();
        for (ResumeAnalysisFact fact : resumeFacts == null ? List.<ResumeAnalysisFact>of() : resumeFacts) {
            AiMatchRequest.Fact mapped = resumeFact(fact);
            if (mapped != null && facts.size() < AiMatchRequest.MAX_FACTS) {
                facts.add(mapped);
            }
        }
        for (AiMatchRequest.Fact fact : contributed == null ? List.<AiMatchRequest.Fact>of() : contributed) {
            if (fact != null && fact.id() != null && fact.value() != null && !fact.value().isBlank()
                    && fact.tier() != null && facts.size() < AiMatchRequest.MAX_FACTS) {
                facts.add(fact);
            }
        }
        for (ClaimEvidence claim : claims == null ? List.<ClaimEvidence>of() : claims) {
            AiMatchRequest.Fact mapped = claimFact(claim);
            if (mapped != null && facts.size() < AiMatchRequest.MAX_FACTS) {
                facts.add(mapped);
            }
        }
        // The AI contract requires totalMonths (>= 0) on a timeline; anything else is not sent.
        JsonNode usableTimeline = timeline != null && timeline.isObject() && timeline.path("totalMonths").canConvertToInt()
                && timeline.path("totalMonths").asInt() >= 0 ? timeline : null;
        return new AiMatchRequest(List.copyOf(aiRequirements), List.copyOf(facts), usableTimeline);
    }

    /** Requirement v2 in the AI contract (also used by the work-sample and interview-kit generators). */
    public static AiMatchRequest.Requirement requirement(JobRequirement requirement) {
        Set<String> alternatives = new LinkedHashSet<>();
        for (String alternative : requirement.getAlternatives()) {
            if (alternative != null && !alternative.isBlank() && alternatives.size() < AiMatchRequest.MAX_ALTERNATIVES) {
                alternatives.add(alternative.trim());
            }
        }
        Integer years = requirement.getMinimumYears() == null ? null : Math.max(0, Math.min(50, requirement.getMinimumYears()));
        // R2-H01: an OTHER requirement is never scored, so it can never gate ranking (legacy rows may still say knockout).
        boolean knockout = requirement.isKnockout() && requirement.getType() != RequirementType.OTHER;
        return new AiMatchRequest.Requirement(requirement.getId(), requirement.getType().name(),
                limit(requirement.getDescription(), 500), requirement.isMandatory(), knockout, years,
                List.copyOf(alternatives), requirement.getProficiency() == null ? null : requirement.getProficiency().name());
    }

    AiMatchRequest.Fact resumeFact(ResumeAnalysisFact fact) {
        if (fact == null || fact.getType() == null || fact.getType().isContact()) {
            return null;
        }
        String value = limit(fact.getExtractedValue(), AiMatchRequest.MAX_FACT_VALUE);
        if (value == null || value.isBlank()) {
            return null;
        }
        EvidenceTier tier = fact.getEvidenceTier() == null ? EvidenceTier.RESUME_STATED : fact.getEvidenceTier();
        JsonNode attributes = JsonText.read(objectMapper, fact.getAttributesJson());
        return new AiMatchRequest.Fact("fact:" + fact.getId(), fact.getType().name(), value,
                limit(fact.getEvidence(), AiMatchRequest.MAX_FACT_EVIDENCE),
                limit(fact.getSourceSection(), AiMatchRequest.MAX_SOURCE_SECTION), tier.name(),
                attributes != null && attributes.isObject() ? attributes : null);
    }

    static AiMatchRequest.Fact claimFact(ClaimEvidence claim) {
        if (claim == null || claim.claimType() == null || claim.rejected()) {
            return null;
        }
        String value = limit(claim.value(), AiMatchRequest.MAX_FACT_VALUE);
        if (value == null || value.isBlank()) {
            return null;
        }
        EvidenceTier tier = claim.tier() == null ? EvidenceTier.SELF_CLAIMED : claim.tier();
        // The evidence URL is not sent: it may identify the candidate, and AI evidence is shown before reveal (D4).
        return new AiMatchRequest.Fact("claim:" + claim.id(), claim.claimType().name(), value, null, null,
                tier.name(), null);
    }

    static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
