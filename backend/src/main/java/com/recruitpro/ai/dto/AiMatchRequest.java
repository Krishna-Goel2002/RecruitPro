package com.recruitpro.ai.dto;

import java.util.List;

public record AiMatchRequest(List<Requirement> requirements, List<Fact> facts) {
    public record Requirement(Long id, String type, String description, boolean mandatory, Integer minimumYears) { }
    public record Fact(Long id, String type, String value, String evidence, String sourceSection, String reviewStatus) { }
}
