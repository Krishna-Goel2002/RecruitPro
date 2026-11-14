package com.recruitpro.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * InterviewQuestion (plan 5.6): {@code id} unique within the kit (e.g. "q1"), linked requirement or null, question,
 * follow-ups and anchors keyed "1".."4". Also the stored form inside {@code interview_kits.questions_json}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InterviewQuestion(String id, Long requirementId, String question, List<String> followUps,
                                Map<String, String> anchors) {
}
