package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** POST /api/v1/requirements/compile response: requirement suggestions with rationale and wording warnings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCompileResponse(String engine, List<Requirement> requirements, List<Warning> warnings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requirement(String type, String description, Boolean mandatory, Boolean knockout, Integer minimumYears,
                              List<String> alternatives, String proficiency, String rationale) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warning(String phrase, String kind, String suggestion) { }
}
