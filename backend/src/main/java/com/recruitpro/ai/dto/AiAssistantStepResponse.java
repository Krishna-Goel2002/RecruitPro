package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/assistant/step response (ASSISTANT_PLAN 3.1): exactly one next step.
 * <ul>
 *   <li>{@code tool_call}: {@code tool}, {@code args}, {@code thought};</li>
 *   <li>{@code ask}: {@code text}, {@code field}, {@code options}, {@code draft};</li>
 *   <li>{@code propose}: {@code action}, {@code args}, {@code summary}, {@code draft};</li>
 *   <li>{@code answer}: {@code text}, {@code blocks}.</li>
 * </ul>
 * {@code meta}: {planner, model, fallback}. The backend validates every field; nothing here is trusted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAssistantStepResponse(String type, String tool, Map<String, Object> args, String thought, String text,
                                      String field, List<Object> options, Map<String, Object> draft, String action,
                                      String summary, List<Map<String, Object>> blocks, Map<String, Object> meta) {
}
