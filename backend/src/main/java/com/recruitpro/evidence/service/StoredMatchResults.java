package com.recruitpro.evidence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.common.util.JsonText;

import java.util.ArrayList;
import java.util.List;

/**
 * Content of {@code job_match_runs.requirement_results_json}: the AI service's per-requirement results and the
 * unverified knockout requirement ids. Runs written before evidence-ladder-3 stored a bare array of results;
 * {@link #read} accepts both.
 */
public record StoredMatchResults(List<AiMatchResponse.RequirementResult> requirements, List<Long> unverifiedKnockouts) {

    public static final StoredMatchResults EMPTY = new StoredMatchResults(List.of(), List.of());

    public String write(ObjectMapper mapper) {
        return JsonText.write(mapper, this);
    }

    public static StoredMatchResults read(ObjectMapper mapper, String json) {
        JsonNode root = JsonText.read(mapper, json);
        if (root == null) {
            return EMPTY;
        }
        JsonNode results = root.isArray() ? root : root.path("requirements");
        List<AiMatchResponse.RequirementResult> requirements = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                try {
                    AiMatchResponse.RequirementResult result = mapper.convertValue(item, AiMatchResponse.RequirementResult.class);
                    if (result != null && result.requirementId() != null) {
                        requirements.add(result);
                    }
                } catch (IllegalArgumentException ignored) {
                    // A malformed stored item is skipped; the requirement shows as not yet assessed.
                }
            }
        }
        List<Long> knockouts = new ArrayList<>();
        JsonNode unverified = root.path("unverifiedKnockouts");
        if (unverified.isArray()) {
            for (JsonNode id : unverified) {
                if (id.canConvertToLong()) {
                    knockouts.add(id.asLong());
                }
            }
        }
        return new StoredMatchResults(List.copyOf(requirements), List.copyOf(knockouts));
    }
}
