package com.recruitpro.ai.dto;

import java.util.List;

public record AiBatchMatchResponse(List<Candidate> candidates) {
    public record Candidate(Long applicationId, Double score, String scoreLabel, String algorithmVersion,
                            List<AiMatchResponse.RequirementMatch> requirements) { }
}
