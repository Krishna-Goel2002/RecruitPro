package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Resume parse result from the AI service (v2 contract): facts with spans and attributes, parser flags
 * (e.g. HIDDEN_TEXT, PROMPT_INJECTION_SUSPECTED) and the role timeline. Unknown properties are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiResumeParseResponse(String status, String errorCode, String parserVersion, List<Fact> facts,
                                    List<Flag> flags, JsonNode timeline) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(String type, String value, String normalizedValue, String evidence,
                       String sourceSection, String sourceReference, Integer spanStart, Integer spanEnd,
                       JsonNode attributes) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flag(String code, String detail) { }
}
