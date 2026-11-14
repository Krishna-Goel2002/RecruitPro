package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/work-samples/generate response: {@code {title, instructions, timeLimitMinutes, rubric, engine}};
 * rubric criteria carry {@code requirementId} (or null), {@code criterion} and anchors keyed "1".."4".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiWorkSampleResponse(String title, String instructions, Integer timeLimitMinutes, List<Criterion> rubric,
                                   String engine) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Criterion(Long requirementId, String criterion, Map<String, String> anchors) { }
}
