package com.recruitpro.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** POST /api/v1/interview-kits/generate response: {@code {questions: InterviewQuestion[], engine}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiInterviewKitResponse(List<Question> questions, String engine) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String id, Long requirementId, String question, List<String> followUps,
                           Map<String, String> anchors) { }
}
