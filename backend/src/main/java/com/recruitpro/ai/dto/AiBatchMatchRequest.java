package com.recruitpro.ai.dto;

import java.util.List;

public record AiBatchMatchRequest(List<AiMatchRequest.Requirement> requirements, List<Candidate> candidates) {
    public record Candidate(Long applicationId, List<AiMatchRequest.Fact> facts) { }
}
