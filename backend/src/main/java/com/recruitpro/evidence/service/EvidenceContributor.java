package com.recruitpro.evidence.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.hr.entity.JobRequirement;

import java.util.List;

/**
 * Extension point for evidence above the resume (D5). WP-BE-C implements it for work samples
 * (tier DEMONSTRATED) and interviews (tier INTERVIEW_VERIFIED); every Spring bean implementing this interface
 * is consulted, in {@code @Order}, each time the MATCH_APPLICATION job builds a match request.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Called inside the job's read-only transaction (lazy associations of {@code application} may be
 *       loaded). Must not write, call the AI service or depend on the current HTTP user (there is none).</li>
 *   <li>Return facts with origin-tagged ids (max 40 characters): {@code ws:<assignmentId>:r<requirementId>}
 *       for work samples and {@code iv:<interviewId>:r<requirementId>} for interviews. The AI service lets such
 *       a fact satisfy only requirement {@code <requirementId>}, so the value only needs to describe it.</li>
 *   <li>Use {@link #fact} to build facts; values are trimmed to the AI service limits.</li>
 *   <li>Return an empty list (never null) when there is nothing to add. Throwing fails the attempt and the job
 *       is retried with backoff.</li>
 *   <li>When the evidence changes (a work sample is scored, a scorecard submitted), call
 *       {@link MatchTriggerService#requestMatch(long)} for the application in the same transaction.</li>
 * </ul>
 */
public interface EvidenceContributor {

    List<AiMatchRequest.Fact> contribute(Application application, List<JobRequirement> requirements);

    /**
     * Builds a match fact. {@code type} is the requirement type (SKILL, EXPERIENCE, EDUCATION, CERTIFICATION or
     * OTHER), {@code tier} its evidence tier, {@code evidence} a short human-readable citation shown to reviewers
     * (no candidate identity) and {@code sourceSection} e.g. "work sample" or "interview".
     */
    static AiMatchRequest.Fact fact(String id, String type, String value, String evidence, String sourceSection,
                                    EvidenceTier tier) {
        return new AiMatchRequest.Fact(
                MatchRequestBuilder.limit(id, AiMatchRequest.MAX_FACT_ID),
                type,
                MatchRequestBuilder.limit(value, AiMatchRequest.MAX_FACT_VALUE),
                MatchRequestBuilder.limit(evidence, AiMatchRequest.MAX_FACT_EVIDENCE),
                MatchRequestBuilder.limit(sourceSection, AiMatchRequest.MAX_SOURCE_SECTION),
                tier == null ? null : tier.name(),
                null);
    }
}
