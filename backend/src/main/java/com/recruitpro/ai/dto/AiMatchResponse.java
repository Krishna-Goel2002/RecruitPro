package com.recruitpro.ai.dto;

import java.util.List;

public record AiMatchResponse(Double score, String scoreLabel, String algorithmVersion, List<RequirementMatch> requirements) {
    public record RequirementMatch(Long requirementId, String status, String evidence, String sourceSection,
                                   String provenance, String explanation) { }
}
