package com.recruitpro.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * RubricCriterion (plan 5.6): {@code requirementId} (a requirement of the job, or null), {@code criterion} and four
 * behavioural anchors keyed "1".."4". Also the stored form inside {@code work_samples.rubric_json}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RubricCriterion(Long requirementId, String criterion, Map<String, String> anchors) {
}
