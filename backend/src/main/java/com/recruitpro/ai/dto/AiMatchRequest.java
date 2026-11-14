package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * POST /api/v1/match request (AI service v2 contract, evidence-ladder-3). Fact ids are origin-tagged strings:
 * {@code fact:<id>} (resume fact), {@code claim:<id>} (candidate claim), {@code ws:<assignment>:r<req>} (work
 * sample) and {@code iv:<interview>:r<req>} (interview). Nulls are omitted, so optional fields fall back to the
 * service defaults.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiMatchRequest(List<Requirement> requirements, List<Fact> facts, JsonNode timeline) {

    /** Limits enforced by the AI service (pydantic); the request builder keeps within them. */
    public static final int MAX_REQUIREMENTS = 100;
    public static final int MAX_FACTS = 500;
    public static final int MAX_ALTERNATIVES = 10;
    public static final int MAX_FACT_VALUE = 1000;
    public static final int MAX_FACT_EVIDENCE = 1000;
    public static final int MAX_SOURCE_SECTION = 80;
    public static final int MAX_FACT_ID = 40;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Requirement(Long id, String type, String description, boolean mandatory, boolean knockout,
                              Integer minimumYears, List<String> alternatives, String proficiency) { }

    /**
     * One piece of evidence. {@code type} is a resume fact type or OTHER (claims); {@code tier} is an
     * {@link com.recruitpro.evidence.entity.EvidenceTier} name; {@code attributes} are the parser attributes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fact(String id, String type, String value, String evidence, String sourceSection, String tier,
                       JsonNode attributes) { }
}
